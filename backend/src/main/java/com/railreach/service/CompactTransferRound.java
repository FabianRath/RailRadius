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

    /**
     * What one station boards, in departure order, and what choosing it took. A round can board ~10 M times on the
     * full data and holds all of them until it rides them, so a boarding is three ints: its departure event with
     * whether the wait passes midnight, the label boarded from and the journey's minutes up to the departure. Its days
     * follow from those ({@link #boardDays}); working out the minutes again would read three more arrays of the label
     * per boarding, which made a large search ~8 % slower.
     */
    static final class StationBoardings {

        private static final int INITIAL_CAPACITY = 8;

        int count;
        // departure event << 1 | extra days (0 or 1)
        private int[] departures = new int[0];
        int[] fromLabels = new int[0];
        int[] boardMinutes = new int[0];
        int departureCount;
        int fallbackScans;
        long fallbackScannedArrivals;

        int departureEvent(int boarding) {
            return departures[boarding] >>> 1;
        }

        private int extraDays(int boarding) {
            return departures[boarding] & 1;
        }

        private void add(int departureEvent, int extraDays, int fromLabel, int minutes) {
            if (count == departures.length) resize(Math.max(INITIAL_CAPACITY, count * 2));
            departures[count] = departureEvent << 1 | extraDays;
            fromLabels[count] = fromLabel;
            boardMinutes[count] = minutes;
            count++;
        }

        /** Every station's boardings wait until the round rides them, so they shouldn't hold spare room. */
        private void trimToSize() {
            if (count < departures.length) resize(count);
        }

        private void resize(int capacity) {
            departures = Arrays.copyOf(departures, capacity);
            fromLabels = Arrays.copyOf(fromLabels, capacity);
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
    // Bit i: arrival firstArrival + i was overtaken
    private long[] overtaken = new long[0];

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
        return (long) (stationStart.length + stationNext.length + grouped.length) * Integer.BYTES
                + (long) overtaken.length * Long.BYTES;
    }

    /** Checked before this round lowers any best durations, so only same-round overtaking drops an arrival. */
    private void groupByStation(int firstArrival, int endArrival) {
        markOvertaken(firstArrival, endArrival);
        Arrays.fill(stationStart, 0);
        int kept = 0;
        for (int label = firstArrival; label < endArrival; label++) {
            if (isMarkedOvertaken(label - firstArrival)) continue;
            stationStart[station(label) + 1]++;
            kept++;
        }
        Arrays.parallelPrefix(stationStart, Integer::sum);
        if (grouped.length < kept) grouped = new int[kept];
        System.arraycopy(stationStart, 0, stationNext, 0, stationStart.length);
        for (int label = firstArrival; label < endArrival; label++) {
            if (!isMarkedOvertaken(label - firstArrival)) grouped[stationNext[station(label)]++] = label;
        }
        stations.clear();
        for (int station = 0; station + 1 < stationStart.length; station++) {
            if (stationStart[station + 1] > stationStart[station]) stations.add(station);
        }
    }

    /** In parallel, each task writing whole words of the bitset; grouping then reads each answer twice. */
    private void markOvertaken(int firstArrival, int endArrival) {
        int count = endArrival - firstArrival;
        int words = (count + Long.SIZE - 1) / Long.SIZE;
        if (overtaken.length < words) overtaken = new long[words];
        IntStream.range(0, words).parallel().forEach(word -> {
            long bits = 0;
            int end = Math.min(count, (word + 1) * Long.SIZE);
            for (int i = word * Long.SIZE; i < end; i++) {
                if (isOvertaken(firstArrival + i)) bits |= 1L << i;
            }
            overtaken[word] = bits;
        });
    }

    private boolean isMarkedOvertaken(int arrival) {
        return (overtaken[arrival / Long.SIZE] & 1L << arrival) != 0;
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
            served |= nextDays & WeekPeriodDays.movedForward(arrivals.arrivals().days()[from], extraDays);
            int fromLabel = arrivals.labels()[from];
            // Staying on this trip beats changing onto it from any arrival
            if (labels.trip(fromLabel) != nextTrip) {
                boardings.add(event, extraDays, fromLabel, boardingMinutes(arrivals, from, departureSeconds));
            }
        }
    }

    /** The days a boarding rides on: those of its trip on which its arrival catches it. */
    long boardDays(StationBoardings boardings, int boarding, int trip, long[] patternDays) {
        return patternDays[store.dayPattern(trip)]
                & WeekPeriodDays.movedForward(labels.weekPeriodDays(boardings.fromLabels[boarding]),
                        boardings.extraDays(boarding));
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
        return progress.reachedFaster(progress.slot(labels.trip(label), alightEvent(label)),
                labels.durationMinutes(label), labels.weekPeriodDays(label));
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
