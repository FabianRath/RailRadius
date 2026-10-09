package com.railreach.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The exact running days of the journeys a compact search chooses among: per label its journey's
 * {@link JourneyRunningDays.SoFar}, like the legacy search's runningDays. Each label's days are worked out once and
 * interned per search, since a station's candidates share their earlier rides and many journeys run on the same days.
 * <p>
 * Candidates ask first ({@link #request}), which also queues every earlier ride of theirs with unknown days.
 * {@link #resolve} then works them out by ascending transfers, each transfer count's labels in parallel: a label needs
 * only the one before its transfer, which has one transfer less. Each label writes only its own slot, after the
 * parallel part, so the result doesn't depend on threads.
 */
final class LabelDays {

    private static final int QUEUED = -2;

    private final LabelArena labels;
    private final List<IntList> queuedByTransfers = new ArrayList<>();
    private DayPatterns.Builder interned = new DayPatterns.Builder();

    LabelDays(LabelArena labels) {
        this.labels = labels;
    }

    void clear() {
        interned = new DayPatterns.Builder();
        queuedByTransfers.forEach(IntList::clear);
    }

    /** Queues the label's days, and those of every earlier ride of its journey that are not known yet. */
    void request(int label) {
        for (int ride = label; ride != LabelArena.NONE && labels.exactDays(ride) == LabelArena.UNKNOWN_DAYS;
             ride = labels.previous(ride)) {
            labels.setExactDays(ride, QUEUED, 0);
            int transfers = labels.transfers(ride);
            while (queuedByTransfers.size() <= transfers) queuedByTransfers.add(new IntList());
            queuedByTransfers.get(transfers).add(ride);
        }
    }

    /** Works out the days of every queued label. */
    void resolve(TimetableStore store, TransitGraph graph) {
        for (IntList queued : queuedByTransfers) {
            JourneyRunningDays.SoFar[] soFar = new JourneyRunningDays.SoFar[queued.size()];
            IntStream.range(0, queued.size()).parallel()
                    .forEach(i -> soFar[i] = soFar(queued.get(i), store, graph));
            for (int i = 0; i < soFar.length; i++) {
                labels.setExactDays(queued.get(i), interned.intern(soFar[i].days()), soFar[i].dayShift());
            }
            queued.clear();
        }
    }

    /** The days of a resolved label's journey. */
    ServiceDays days(int label) {
        return interned.days(labels.exactDays(label));
    }

    private JourneyRunningDays.SoFar soFar(int label, TimetableStore store, TransitGraph graph) {
        int previous = labels.previous(label);
        JourneyRunningDays.SoFar before = previous == LabelArena.NONE ? JourneyRunningDays.SoFar.START
                : new JourneyRunningDays.SoFar(days(previous), labels.dayShift(previous),
                        store.arrivalSeconds(alightEvent(previous, store)));
        int boardEvent = store.firstEvent(labels.trip(label)) + labels.boardIdx(label);
        int alightEvent = alightEvent(label, store);
        return JourneyRunningDays.withRide(before, store.rideDays(boardEvent, alightEvent),
                store.departureSeconds(boardEvent), store.arrivalSeconds(alightEvent), graph);
    }

    private int alightEvent(int label, TimetableStore store) {
        return store.firstEvent(labels.trip(label)) + labels.alightIdx(label);
    }
}
