package com.railradius.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The exact running days of the journeys a compact search chooses among: per label its journey's
 * {@link JourneyRunningDays.SoFar}. Each label's days are worked out once and interned per search, since a station's
 * candidates share their earlier rides and many journeys run on the same days.
 * The memo is one int per label in the arena: the interned days' index and the day shift, packed. A side table by
 * label would be smaller, since only a few percent of the labels need it, but its lookups made choosing paths ~40 %
 * slower.
 * <p>
 * Candidates ask first ({@link #request}), which also queues every earlier ride of theirs with unknown days.
 * {@link #resolve} then works them out by ascending transfers, each transfer count's labels in parallel: a label needs
 * only the one before its transfer, which has one transfer less. Each label writes only its own slot, after the
 * parallel part, so the result doesn't depend on threads.
 * <p>
 * A ride's own days ({@link TimetableStore#rideDays}) scan its trip's twins and allocate, and many labels ride the
 * same stretch of a trip, so they are worked out once per board and alight event in a search (#44).
 */
final class LabelDays {

    private static final int QUEUED = -2;
    // A memo is the interned days' index << DAY_SHIFT_BITS | the day shift: at most one per transfer, and a search
    // has fewer than 128
    private static final int DAY_SHIFT_BITS = Byte.SIZE - 1;
    private static final int DAY_SHIFT_MASK = (1 << DAY_SHIFT_BITS) - 1;
    private static final int MAX_DAYS_INDEX = Integer.MAX_VALUE >> DAY_SHIFT_BITS;

    private final LabelArena labels;
    private final List<IntList> queuedByTransfers = new ArrayList<>();
    // Filled between the parallel parts, so they only read it
    private final Map<Long, ServiceDays> rideDaysByEvents = new HashMap<>();
    private DayPatterns.Builder interned = new DayPatterns.Builder();

    LabelDays(LabelArena labels) {
        this.labels = labels;
    }

    void clear() {
        interned = new DayPatterns.Builder();
        queuedByTransfers.forEach(IntList::clear);
        rideDaysByEvents.clear();
    }

    /** Queues the label's days, and those of every earlier ride of its journey that are not known yet. */
    void request(int label) {
        for (int ride = label; ride != LabelArena.NONE && labels.daysMemo(ride) == LabelArena.UNKNOWN_DAYS;
             ride = labels.previous(ride)) {
            labels.setDaysMemo(ride, QUEUED);
            int transfers = labels.transfers(ride);
            while (queuedByTransfers.size() <= transfers) queuedByTransfers.add(new IntList());
            queuedByTransfers.get(transfers).add(ride);
        }
    }

    /** Works out the days of every queued label. */
    void resolve(TimetableStore store, TransitGraph graph) {
        for (IntList queued : queuedByTransfers) {
            addRideDays(queued, store);
            JourneyRunningDays.SoFar[] soFar = new JourneyRunningDays.SoFar[queued.size()];
            Parallel.forEach(queued.size(), i -> soFar[i] = soFar(queued.get(i), store, graph));
            for (int i = 0; i < soFar.length; i++) {
                int daysIndex = interned.intern(soFar[i].days());
                if (daysIndex > MAX_DAYS_INDEX) throw new IllegalStateException("Too many distinct journey days");
                labels.setDaysMemo(queued.get(i), daysIndex << DAY_SHIFT_BITS | soFar[i].dayShift());
            }
            queued.clear();
        }
    }

    /** Works out, in parallel, the ride days of the labels' rides that no earlier label of the search rode. */
    private void addRideDays(IntList queued, TimetableStore store) {
        long[] missing = new long[queued.size()];
        int missingCount = 0;
        for (int i = 0; i < queued.size(); i++) {
            long rideKey = rideKey(queued.get(i), store);
            if (rideDaysByEvents.containsKey(rideKey)) continue;
            rideDaysByEvents.put(rideKey, null);
            missing[missingCount++] = rideKey;
        }
        ServiceDays[] days = new ServiceDays[missingCount];
        Parallel.forEach(missingCount,
                i -> days[i] = store.rideDays(boardEventOf(missing[i]), alightEventOf(missing[i])));
        for (int i = 0; i < missingCount; i++) {
            rideDaysByEvents.put(missing[i], days[i]);
        }
    }

    /** The days of a resolved label's journey. */
    ServiceDays days(int label) {
        return interned.days(labels.daysMemo(label) >> DAY_SHIFT_BITS);
    }

    private JourneyRunningDays.SoFar soFar(int label, TimetableStore store, TransitGraph graph) {
        int previous = labels.previous(label);
        JourneyRunningDays.SoFar before = previous == LabelArena.NONE ? JourneyRunningDays.SoFar.START
                : new JourneyRunningDays.SoFar(days(previous), labels.daysMemo(previous) & DAY_SHIFT_MASK,
                        store.arrivalSeconds(alightEvent(previous, store)));
        long rideKey = rideKey(label, store);
        return JourneyRunningDays.withRide(before, store, boardEventOf(rideKey), alightEventOf(rideKey),
                rideDaysByEvents.get(rideKey), graph);
    }

    /** The label's board event in the high half, its alight event in the low half. */
    private long rideKey(int label, TimetableStore store) {
        int boardEvent = store.firstEvent(labels.trip(label)) + labels.boardIdx(label);
        return (long) boardEvent << Integer.SIZE | alightEvent(label, store);
    }

    private static int boardEventOf(long rideKey) {
        return (int) (rideKey >>> Integer.SIZE);
    }

    private static int alightEventOf(long rideKey) {
        return (int) rideKey;
    }

    private int alightEvent(int label, TimetableStore store) {
        return store.firstEvent(labels.trip(label)) + labels.alightIdx(label);
    }
}
