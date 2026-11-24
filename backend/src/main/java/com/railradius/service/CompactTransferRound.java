package com.railradius.service;

import com.railradius.util.GtfsTime;

import java.util.Arrays;
import java.util.List;

/**
 * The transfer part of a round of {@link CompactReachabilitySearch}: the arrivals of the previous round's
 * {@link RoundRides}, minus those a faster ride of their round overtook, grouped by station index, and what each
 * station's departures board from them. Stations choose in parallel. A station's sorted arrivals and transfer windows
 * live only while it chooses, so the round keeps about 8 bytes per arrival. Only the arrivals boarded from get a
 * label ({@link #labelBoardedFrom}).
 */
final class CompactTransferRound {

    private static final int SAME_DAY = 0;
    private static final int NEXT_DAY = 1;
    private static final int SECONDS_SHIFT = Integer.SIZE;
    private static final long POSITION_MASK = 0xFFFF_FFFFL;
    // A grouped arrival is its ride << RIDE_SHIFT | its stops after the board event << MINUTES_BITS | the minutes
    // since boarding; a ride index fits the 31 bits left
    private static final int MINUTES_BITS = 18;
    private static final int STOPS_BITS = 15;
    private static final int RIDE_SHIFT = MINUTES_BITS + STOPS_BITS;
    // record >>> WORD_SHIFT is the record's word in dropped
    private static final int WORD_SHIFT = 6;

    /** A search's trips' days by day pattern ({@link WeekPeriodDays}) and its wait window. */
    record Parameters(long[] patternDays, int minWaitSeconds, int maxWaitSeconds) {}

    /**
     * What one station boards, in departure order, and what choosing it took. A round can board ~10 M times on the
     * full data and holds them until it rides them, so a boarding is two ints and a char: its departure event with
     * whether the wait passes midnight, the arrival boarded from and the journey's minutes up to the departure. Its
     * days follow from those ({@link #boardDays}); working out the minutes again would read three more arrays of the
     * label per boarding, which made a large search ~8 % slower. Several departures board from one arrival, so the
     * station lists the arrivals it boards from once each.
     */
    static final class StationBoardings {

        private static final int INITIAL_CAPACITY = 8;
        // Most stations board nothing; growing copies, so they can share these
        private static final int[] NO_INTS = new int[0];
        private static final char[] NO_MINUTES = new char[0];

        int count;
        // departure event << 1 | extra days (0 or 1)
        private int[] departures = NO_INTS;
        // The arrival boarded from as an index into fromPositions, until labelBoardedFrom makes it the arrival's label
        int[] from = NO_INTS;
        char[] boardMinutes = NO_MINUTES;
        // The grouped positions of the arrivals boarded from, each once
        private int[] fromPositions = NO_INTS;
        private int fromCount;
        int departureCount;
        int fallbackScans;
        long fallbackScannedArrivals;

        int departureEvent(int boarding) {
            return departures[boarding] >>> 1;
        }

        private int extraDays(int boarding) {
            return departures[boarding] & 1;
        }

        private void add(int departureEvent, int extraDays, int fromIndex, int minutes) {
            if (count == departures.length) resize(Math.max(INITIAL_CAPACITY, count * 2));
            departures[count] = departureEvent << 1 | extraDays;
            from[count] = fromIndex;
            boardMinutes[count] = PackedMinutes.of(minutes);
            count++;
        }

        /** Returns the arrival's index into fromPositions. */
        private int addFrom(int position) {
            if (fromCount == fromPositions.length) {
                fromPositions = Arrays.copyOf(fromPositions, Math.max(INITIAL_CAPACITY, fromCount * 2));
            }
            fromPositions[fromCount] = position;
            return fromCount++;
        }

        /** Every station's boardings wait until the round rides them, so they shouldn't hold spare room. */
        private void trimToSize() {
            if (count < departures.length) resize(count);
            if (fromCount < fromPositions.length) fromPositions = Arrays.copyOf(fromPositions, fromCount);
        }

        /** Once ridden, so the round holds only the boardings it has yet to ride. */
        void release() {
            departures = NO_INTS;
            from = NO_INTS;
            boardMinutes = NO_MINUTES;
            fromPositions = NO_INTS;
        }

        private void resize(int capacity) {
            departures = Arrays.copyOf(departures, capacity);
            from = Arrays.copyOf(from, capacity);
            boardMinutes = Arrays.copyOf(boardMinutes, capacity);
        }
    }

    /** One station's arrivals sorted by arrival time, with each one's grouped position, while the station chooses. */
    private record StationArrivals(int[] positions, TransferWindowInts.Arrivals arrivals) {}

    private final TimetableStore store;
    private final LabelArena labels;
    private final TripProgressFlat progress;
    private final RoundRides rides;
    // Per station index: the first position of its arrivals in grouped; one more entry for the end
    private final int[] stationStart;
    private final int[] stationNext;
    private final IntList stations = new IntList();
    // The round's arrivals, each station's together, in the order they came
    private long[] grouped = new long[0];
    // A bit per record of the rides: whether its arrival is left out, being overtaken or at the start station
    private long[] dropped = new long[0];

    CompactTransferRound(TimetableStore store, LabelArena labels, TripProgressFlat progress, RoundRides rides) {
        this.store = store;
        this.labels = labels;
        this.progress = progress;
        this.rides = rides;
        this.stationStart = new int[store.stations().size() + 1];
        this.stationNext = new int[store.stations().size() + 1];
    }

    /** What each station boards from the arrivals of the rides, by ascending station index. */
    List<StationBoardings> boardings(int startStation, Parameters parameters) {
        groupByStation(startStation);
        return Parallel.range(stations.size())
                .mapToObj(i -> boardingsAt(stations.get(i), parameters))
                .toList();
    }

    /** Marks, for the arena's next trim, the label each boarded-from arrival's ride boarded from, and its chain. */
    void keepBoardedFrom(List<StationBoardings> boarded, LabelArena arena) {
        for (StationBoardings station : boarded) {
            for (int i = 0; i < station.fromCount; i++) {
                int previous = rides.previous(rideOf(grouped[station.fromPositions[i]]));
                if (previous != LabelArena.NONE) arena.keepWithEarlierRides(previous);
            }
        }
    }

    /**
     * After the arena's trim: gives each arrival boarded from a label, the stations' in station order, and makes the
     * boardings point to the labels. Stations write only their own labels, so they do it in parallel.
     */
    void labelBoardedFrom(List<StationBoardings> boarded) {
        int[] firstLabel = new int[boarded.size()];
        int fromTotal = 0;
        for (int i = 0; i < boarded.size(); i++) {
            firstLabel[i] = fromTotal;
            fromTotal += boarded.get(i).fromCount;
        }
        int first = labels.reserve(fromTotal);
        Parallel.range(boarded.size()).forEach(i -> label(boarded.get(i), first + firstLabel[i]));
    }

    long sizeBytes() {
        return (long) (stationStart.length + stationNext.length) * Integer.BYTES + (long) grouped.length * Long.BYTES
                + (long) dropped.length * Long.BYTES;
    }

    private void label(StationBoardings station, int firstLabel) {
        for (int i = 0; i < station.fromCount; i++) {
            long arrival = grouped[station.fromPositions[i]];
            int ride = rideOf(arrival);
            int boardEvent = rides.boardEvent(ride);
            int trip = store.tripOf(boardEvent);
            int boardIdx = boardEvent - store.firstEvent(trip);
            int previous = rides.previous(ride);
            labels.set(firstLabel + i, trip, boardIdx, boardIdx + stopsOf(arrival), durationMinutes(arrival),
                    previous == LabelArena.NONE ? LabelArena.NONE : labels.trimmedIndex(previous), rides.days(ride));
        }
        for (int i = 0; i < station.count; i++) {
            station.from[i] += firstLabel;
        }
    }

    /**
     * Arrivals at the start station are left out: a round never offers them. Overtaking is checked before this round
     * lowers any best durations, so only same-round overtaking drops an arrival.
     */
    private void groupByStation(int startStation) {
        int words = (rides.recordCount() + Long.SIZE - 1) >>> WORD_SHIFT;
        if (dropped.length < words) {
            dropped = new long[words];
        } else {
            Arrays.fill(dropped, 0, words, 0L);
        }
        Parallel.range(rides.size()).forEach(ride -> markDropped(ride, startStation));
        Arrays.fill(stationStart, 0);
        int kept = 0;
        for (int ride = 0; ride < rides.size(); ride++) {
            int end = rides.recordStart(ride + 1);
            for (int record = rides.recordStart(ride), event = rides.boardEvent(ride) + 1; record < end;
                 record++, event++) {
                if (isDropped(record)) continue;
                stationStart[store.station(event) + 1]++;
                kept++;
            }
        }
        Parallel.prefixSum(stationStart);
        if (grouped.length < kept) grouped = new long[kept];
        System.arraycopy(stationStart, 0, stationNext, 0, stationStart.length);
        for (int ride = 0; ride < rides.size(); ride++) {
            int boardEvent = rides.boardEvent(ride);
            int end = rides.recordStart(ride + 1);
            int minutes = 0;
            for (int record = rides.recordStart(ride), event = boardEvent + 1; record < end; record++, event++) {
                minutes += store.rideLegMinutes(boardEvent, event - 1);
                if (!isDropped(record)) {
                    grouped[stationNext[store.station(event)]++] = arrival(ride, event - boardEvent, minutes);
                }
            }
        }
        stations.clear();
        for (int station = 0; station + 1 < stationStart.length; station++) {
            if (stationStart[station + 1] > stationStart[station]) stations.add(station);
        }
    }

    /** Sets the bits of the ride's dropped records, a word at a time. Rides do it in parallel. */
    private void markDropped(int ride, int startStation) {
        int boardEvent = rides.boardEvent(ride);
        int trip = store.tripOf(boardEvent);
        long days = rides.days(ride);
        int minutes = rides.boardMinutes(ride);
        int end = rides.recordStart(ride + 1);
        long bits = 0;
        for (int record = rides.recordStart(ride), event = boardEvent + 1; record < end; record++, event++) {
            minutes += store.rideLegMinutes(boardEvent, event - 1);
            if (store.station(event) == startStation
                    || progress.reachedFaster(progress.slot(trip, event), minutes, days)) {
                bits |= 1L << record;
            }
            boolean wordEnds = record + 1 == end || ((record + 1) & (Long.SIZE - 1)) == 0;
            if (wordEnds && bits != 0) {
                Parallel.orInto(dropped, record >>> WORD_SHIFT, bits);
                bits = 0;
            }
        }
    }

    private boolean isDropped(int record) {
        return (dropped[record >>> WORD_SHIFT] & 1L << record) != 0;
    }

    private StationBoardings boardingsAt(int station, Parameters parameters) {
        StationArrivals arrivals = sortedArrivals(stationStart[station], stationStart[station + 1]);
        int count = arrivals.positions().length;
        TransferWindowInts sameDay = new TransferWindowInts(arrivals.arrivals(), new int[count], 0,
                parameters.minWaitSeconds(), parameters.maxWaitSeconds());
        TransferWindowInts overnight = new TransferWindowInts(arrivals.arrivals(), new int[count],
                -GtfsTime.SECONDS_PER_DAY, parameters.minWaitSeconds(),
                Math.min(parameters.maxWaitSeconds(), GtfsTime.MAX_OVERNIGHT_GAP_SECONDS));
        StationBoardings boardings = new StationBoardings();
        // Per sorted arrival: its index into the boardings' fromPositions, -1 while nothing boards from it
        int[] fromIndex = new int[count];
        Arrays.fill(fromIndex, -1);
        int departuresEnd = store.departuresEnd(station);
        for (int position = store.departuresStart(station); position < departuresEnd; position++) {
            board(store.departureEventAt(position), store.departureSecondsAt(position), arrivals, sameDay, overnight,
                    parameters.patternDays(), fromIndex, boardings);
        }
        boardings.trimToSize();
        boardings.departureCount = departuresEnd - store.departuresStart(station);
        boardings.fallbackScans = sameDay.fallbackScanCount() + overnight.fallbackScanCount();
        boardings.fallbackScannedArrivals = sameDay.fallbackScannedArrivals() + overnight.fallbackScannedArrivals();
        return boardings;
    }

    /**
     * Stable (Search semantics contract): equal times keep the order the arrivals came in, which is their grouped
     * order, so sorting by arrival seconds and then grouped position does it.
     */
    private StationArrivals sortedArrivals(int from, int to) {
        TransferWindowInts.Arrivals arrivals = TransferWindowInts.Arrivals.ofCapacity(to - from);
        long[] days = arrivals.days();
        for (int i = 0; i < days.length; i++) {
            days[i] = (long) arrivalSeconds(grouped[from + i]) << SECONDS_SHIFT | (from + i);
        }
        Arrays.sort(days);
        int[] positions = new int[days.length];
        for (int i = 0; i < days.length; i++) {
            int position = (int) (days[i] & POSITION_MASK);
            int seconds = (int) (days[i] >>> SECONDS_SHIFT);
            long arrival = grouped[position];
            positions[i] = position;
            arrivals.arrivalSeconds()[i] = seconds;
            arrivals.startSeconds()[i] = seconds - durationMinutes(arrival) * GtfsTime.SECONDS_PER_MINUTE;
            days[i] = rides.days(rideOf(arrival));
        }
        arrivals.summarizeBlocks();
        return new StationArrivals(positions, arrivals);
    }

    /**
     * Boards the departure from the arrival that catches it with the shortest journey so far, then from slower ones
     * only for days of the departing trip that no faster one runs on (B33).
     */
    private void board(int event, int departureSeconds, StationArrivals arrivals, TransferWindowInts sameDay,
                       TransferWindowInts overnight, long[] patternDays, int[] fromIndex,
                       StationBoardings boardings) {
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
            long rideDays = nextDays & WeekPeriodDays.movedForward(arrivals.arrivals().days()[from], extraDays);
            served |= rideDays;
            int fromPosition = arrivals.positions()[from];
            int minutes = boardingMinutes(arrivals, from, departureSeconds);
            // Staying on this trip beats changing onto it from any arrival
            if (tripOf(grouped[fromPosition]) != nextTrip && ridesOn(event, nextTrip, minutes, rideDays)) {
                if (fromIndex[from] < 0) fromIndex[from] = boardings.addFrom(fromPosition);
                boardings.add(event, extraDays, fromIndex[from], minutes);
            }
        }
    }

    /**
     * Whether riding the boarding could record the trip's next stop: a ride that can't records nothing, so it is not
     * boarded at all, and its arrival needs no label. A stop that earlier rides reached no slower on all the ride's
     * days stays so for the rest of the round, since riding only lowers durations and adds days. On the full data,
     * most of a large search's boardings are dropped this way.
     */
    private boolean ridesOn(int event, int trip, int boardMinutes, long rideDays) {
        if (!store.hasLeg(event)) return false;
        if (!progress.reached(trip)) return true;
        return !progress.reachedNoSlower(progress.slot(trip, event + 1), boardMinutes + store.legMinutes(event),
                rideDays);
    }

    /** The days a boarding rides on: those of its trip on which its arrival catches it. Needs its label. */
    long boardDays(StationBoardings boardings, int boarding, int trip, long[] patternDays) {
        return patternDays[store.dayPattern(trip)]
                & WeekPeriodDays.movedForward(labels.weekPeriodDays(boardings.from[boarding]),
                        boardings.extraDays(boarding));
    }

    /** Overnight wins only when it is strictly shorter. */
    private int faster(int departureSeconds, StationArrivals arrivals, int sameDay, int overnight) {
        if (sameDay < 0 || overnight < 0) return sameDay >= 0 ? sameDay : overnight;
        return boardingMinutes(arrivals, overnight, departureSeconds)
                < boardingMinutes(arrivals, sameDay, departureSeconds) ? overnight : sameDay;
    }

    /** Journey time up to the departure, the wait included. The journey started whole minutes before the arrival. */
    private static int boardingMinutes(StationArrivals arrivals, int position, int departureSeconds) {
        int arrivalSeconds = arrivals.arrivals().arrivalSeconds()[position];
        return (arrivalSeconds - arrivals.arrivals().startSeconds()[position]) / GtfsTime.SECONDS_PER_MINUTE
                + GtfsTime.diffMinutes(arrivalSeconds, departureSeconds);
    }

    private long arrival(int ride, int stops, int minutes) {
        if (stops >= 1 << STOPS_BITS || minutes >= 1 << MINUTES_BITS) {
            throw new IllegalStateException("Ride " + ride + " of trip " + store.tripOf(rides.boardEvent(ride))
                    + " is too long to group: " + stops + " stops, " + minutes + " minutes");
        }
        return (long) ride << RIDE_SHIFT | (long) stops << MINUTES_BITS | minutes;
    }

    private static int rideOf(long arrival) {
        return (int) (arrival >>> RIDE_SHIFT);
    }

    private static int stopsOf(long arrival) {
        return (int) (arrival >>> MINUTES_BITS) & ((1 << STOPS_BITS) - 1);
    }

    private int durationMinutes(long arrival) {
        return rides.boardMinutes(rideOf(arrival)) + (int) (arrival & ((1 << MINUTES_BITS) - 1));
    }

    private int arrivalSeconds(long arrival) {
        return store.arrivalSeconds(rides.boardEvent(rideOf(arrival)) + stopsOf(arrival));
    }

    private int tripOf(long arrival) {
        return store.tripOf(rides.boardEvent(rideOf(arrival)));
    }
}
