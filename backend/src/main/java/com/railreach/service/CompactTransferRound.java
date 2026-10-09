package com.railreach.service;

import com.railreach.util.GtfsTime;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The transfer part of a round of {@link CompactReachabilitySearch}, like the legacy search's transferRound,
 * boardingsAt and board: the previous round's arrivals, minus those a faster ride of their round overtook, grouped by
 * station index, and what each station's departures board from them. Stations choose in parallel. A station's
 * sorted arrivals and transfer windows live only while it chooses, so the round keeps 4 bytes per arrival.
 */
final class CompactTransferRound {

    private static final int SAME_DAY = 0;
    private static final int NEXT_DAY = 1;
    private static final int SECONDS_SHIFT = Integer.SIZE;
    private static final long LABEL_MASK = 0xFFFF_FFFFL;

    /** A search's trips' days by day pattern ({@link WeekPeriodDays}) and its wait window. */
    record Parameters(long[] patternDays, int minWaitSeconds, int maxWaitSeconds) {}

    /** What one station boards, in departure order, and what choosing it took. */
    static final class StationBoardings {

        private static final int INITIAL_CAPACITY = 8;

        int count;
        int[] departureEvents = new int[0];
        int[] fromLabels = new int[0];
        long[] days = new long[0];
        int[] boardMinutes = new int[0];
        int departureCount;
        int fallbackScans;
        long fallbackScannedArrivals;

        private void add(int departureEvent, int fromLabel, long boardDays, int minutes) {
            if (count == departureEvents.length) resize(Math.max(INITIAL_CAPACITY, count * 2));
            departureEvents[count] = departureEvent;
            fromLabels[count] = fromLabel;
            days[count] = boardDays;
            boardMinutes[count] = minutes;
            count++;
        }

        /** Every station's boardings wait until the round rides them, so they shouldn't hold spare room. */
        private void trimToSize() {
            if (count < departureEvents.length) resize(count);
        }

        private void resize(int capacity) {
            departureEvents = Arrays.copyOf(departureEvents, capacity);
            fromLabels = Arrays.copyOf(fromLabels, capacity);
            days = Arrays.copyOf(days, capacity);
            boardMinutes = Arrays.copyOf(boardMinutes, capacity);
        }
    }

    /** One station's arrivals sorted by arrival time, with each one's label, while the station chooses. */
    private record StationArrivals(int[] labels, TransferWindowInts.Arrivals arrivals) {}

    private final TimetableStore store;
    private final LabelArena labels;
    private final TripProgressFlat progress;
    // Per station index: the first position of its arrivals in grouped; one more entry for the end
    private final int[] stationStart;
    private final int[] stationNext;
    private final IntList stations = new IntList();
    // The round's arrivals, each station's together, in the order they came
    private int[] grouped = new int[0];

    CompactTransferRound(TimetableStore store, LabelArena labels, TripProgressFlat progress) {
        this.store = store;
        this.labels = labels;
        this.progress = progress;
        this.stationStart = new int[store.stations().size() + 1];
        this.stationNext = new int[store.stations().size() + 1];
    }

    /** What each station boards from the arrivals firstArrival up to endArrival, by ascending station index. */
    List<StationBoardings> boardings(int firstArrival, int endArrival, Parameters parameters) {
        groupByStation(firstArrival, endArrival);
        return IntStream.range(0, stations.size()).parallel()
                .mapToObj(i -> boardingsAt(stations.get(i), parameters))
                .toList();
    }

    long sizeBytes() {
        return (long) (stationStart.length + stationNext.length + grouped.length) * Integer.BYTES;
    }

    /** Checked before this round lowers any best durations, so only same-round overtaking drops an arrival. */
    private void groupByStation(int firstArrival, int endArrival) {
        Arrays.fill(stationStart, 0);
        int kept = 0;
        for (int label = firstArrival; label < endArrival; label++) {
            if (isOvertaken(label)) continue;
            stationStart[station(label) + 1]++;
            kept++;
        }
        Arrays.parallelPrefix(stationStart, Integer::sum);
        if (grouped.length < kept) grouped = new int[kept];
        System.arraycopy(stationStart, 0, stationNext, 0, stationStart.length);
        for (int label = firstArrival; label < endArrival; label++) {
            if (!isOvertaken(label)) grouped[stationNext[station(label)]++] = label;
        }
        stations.clear();
        for (int station = 0; station + 1 < stationStart.length; station++) {
            if (stationStart[station + 1] > stationStart[station]) stations.add(station);
        }
    }

    private StationBoardings boardingsAt(int station, Parameters parameters) {
        StationArrivals arrivals = sortedArrivals(stationStart[station], stationStart[station + 1]);
        int count = arrivals.labels().length;
        TransferWindowInts sameDay = new TransferWindowInts(arrivals.arrivals(), new int[count], 0,
                parameters.minWaitSeconds(), parameters.maxWaitSeconds());
        TransferWindowInts overnight = new TransferWindowInts(arrivals.arrivals(), new int[count],
                -GtfsTime.SECONDS_PER_DAY, parameters.minWaitSeconds(),
                Math.min(parameters.maxWaitSeconds(), GtfsTime.MAX_OVERNIGHT_GAP_SECONDS));
        StationBoardings boardings = new StationBoardings();
        int departuresEnd = store.departuresEnd(station);
        for (int position = store.departuresStart(station); position < departuresEnd; position++) {
            board(store.departureEventAt(position), store.departureSecondsAt(position), arrivals, sameDay, overnight,
                    parameters.patternDays(), boardings);
        }
        boardings.trimToSize();
        boardings.departureCount = departuresEnd - store.departuresStart(station);
        boardings.fallbackScans = sameDay.fallbackScanCount() + overnight.fallbackScanCount();
        boardings.fallbackScannedArrivals = sameDay.fallbackScannedArrivals() + overnight.fallbackScannedArrivals();
        return boardings;
    }

    /**
     * Stable, like the legacy search's sort: equal times keep the order the arrivals came in, which is label order,
     * so sorting by arrival seconds and then label does it.
     */
    private StationArrivals sortedArrivals(int from, int to) {
        TransferWindowInts.Arrivals arrivals = TransferWindowInts.Arrivals.ofCapacity(to - from);
        long[] days = arrivals.days();
        for (int i = 0; i < days.length; i++) {
            int label = grouped[from + i];
            days[i] = (long) arrivalSecondsOf(label) << SECONDS_SHIFT | label;
        }
        Arrays.sort(days);
        int[] sortedLabels = new int[days.length];
        for (int i = 0; i < days.length; i++) {
            int label = (int) (days[i] & LABEL_MASK);
            int seconds = (int) (days[i] >>> SECONDS_SHIFT);
            sortedLabels[i] = label;
            arrivals.arrivalSeconds()[i] = seconds;
            arrivals.startSeconds()[i] = seconds - labels.durationMinutes(label) * GtfsTime.SECONDS_PER_MINUTE;
            days[i] = labels.weekPeriodDays(label);
        }
        arrivals.summarizeBlocks();
        return new StationArrivals(sortedLabels, arrivals);
    }

    /**
     * Boards the departure from the arrival that catches it with the shortest journey so far, then from slower ones
     * only for days of the departing trip that no faster one runs on (B33), as the legacy search's board does.
     */
    private void board(int event, int departureSeconds, StationArrivals arrivals, TransferWindowInts sameDay,
                       TransferWindowInts overnight, long[] patternDays, StationBoardings boardings) {
        if (!store.isRegularDeparture(event)) return;
        int nextTrip = store.tripOf(event);
        long nextDays = patternDays[store.dayPattern(nextTrip)];
        long served = 0;
        while ((nextDays & ~served) != 0) {
            int sameDayFrom = sameDay.bestFor(departureSeconds, nextDays, SAME_DAY, served);
            int overnightFrom = overnight.bestFor(departureSeconds, nextDays, NEXT_DAY, served);
            int from = faster(departureSeconds, arrivals, sameDayFrom, overnightFrom);
            if (from < 0) return;
            int extraDays = from == sameDayFrom ? SAME_DAY : NEXT_DAY;
            long days = nextDays & WeekPeriodDays.movedForward(arrivals.arrivals().days()[from], extraDays);
            served |= days;
            int fromLabel = arrivals.labels()[from];
            // Staying on this trip beats changing onto it from any arrival
            if (labels.trip(fromLabel) != nextTrip) {
                boardings.add(event, fromLabel, days, boardingMinutes(arrivals, from, departureSeconds));
            }
        }
    }

    /** Overnight wins only when it is strictly shorter. */
    private int faster(int departureSeconds, StationArrivals arrivals, int sameDay, int overnight) {
        if (sameDay < 0 || overnight < 0) return sameDay >= 0 ? sameDay : overnight;
        return boardingMinutes(arrivals, overnight, departureSeconds)
                < boardingMinutes(arrivals, sameDay, departureSeconds) ? overnight : sameDay;
    }

    /** Journey time up to the departure, the wait included. */
    private int boardingMinutes(StationArrivals arrivals, int position, int departureSeconds) {
        return labels.durationMinutes(arrivals.labels()[position])
                + GtfsTime.diffMinutes(arrivals.arrivals().arrivalSeconds()[position], departureSeconds);
    }

    /** A faster ride of the same round on all its days reached the same stop of the same trip later on. */
    private boolean isOvertaken(int label) {
        return progress.reachedFaster(alightEvent(label), labels.durationMinutes(label), labels.weekPeriodDays(label));
    }

    private int station(int label) {
        return store.station(alightEvent(label));
    }

    private int arrivalSecondsOf(int label) {
        return store.arrivalSeconds(alightEvent(label));
    }

    private int alightEvent(int label) {
        return store.firstEvent(labels.trip(label)) + labels.alightIdx(label);
    }
}
