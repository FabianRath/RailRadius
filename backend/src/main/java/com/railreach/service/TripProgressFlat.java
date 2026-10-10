package com.railreach.service;

import java.util.Arrays;

/**
 * Per stop of every trip a search reaches: the shortest duration that reached it there, the days of that ride, and
 * the days of every ride recorded there. Rides of one trip arrive at a stop at the same time and catch the same trains
 * from there, so a ride is cut off where it is no faster and all its days are already reached. On some of those days
 * the journey shown can then be a bit slower than the fastest possible.
 * <p>
 * Reused from search to search. A trip's stop events get slots
 * when the search first reaches it, marked by stamping the trip with the search's generation, so starting a search
 * costs nothing. A large search reaches about half the store's events, so slots are only made for reached trips.
 */
final class TripProgressFlat {

    private static final int INITIAL_CAPACITY = 1 << 16;

    private final int eventCount;
    // Per trip: the slot of its first event minus that event, valid while the trip carries this search's generation
    private final int[] tripBase;
    private final int[] tripGeneration;
    private int generation;
    private int[] durationMinutes;
    private long[] fastestDays;
    private long[] reachedDays;
    private int usedSlots;

    TripProgressFlat(TimetableStore store) {
        eventCount = store.eventCount();
        tripBase = new int[store.tripCount()];
        tripGeneration = new int[store.tripCount()];
        int capacity = Math.min(INITIAL_CAPACITY, eventCount);
        durationMinutes = new int[capacity];
        fastestDays = new long[capacity];
        reachedDays = new long[capacity];
    }

    void startSearch() {
        usedSlots = 0;
        generation++;
        // After 2^32 searches a stamp could match again
        if (generation == 0) {
            Arrays.fill(tripGeneration, 0);
            generation = 1;
        }
    }

    /**
     * Gives the trip's events, firstEvent up to endEvent, unreached slots if this search has not reached the trip yet.
     * Returns what to add to one of its events to get the event's slot.
     */
    int reach(int trip, int firstEvent, int endEvent) {
        if (reached(trip)) return tripBase[trip];
        tripGeneration[trip] = generation;
        int end = usedSlots + endEvent - firstEvent;
        if (end > durationMinutes.length) grow(end);
        Arrays.fill(durationMinutes, usedSlots, end, Integer.MAX_VALUE);
        Arrays.fill(fastestDays, usedSlots, end, 0L);
        Arrays.fill(reachedDays, usedSlots, end, 0L);
        tripBase[trip] = usedSlots - firstEvent;
        usedSlots = end;
        return tripBase[trip];
    }

    /** Whether this search reached the trip, so its events have slots. */
    boolean reached(int trip) {
        return tripGeneration[trip] == generation;
    }

    /** The slot of an event of a trip this search reached. */
    int slot(int trip, int event) {
        return tripBase[trip] + event;
    }

    boolean reachedNoSlower(int slot, int rideMinutes, long rideDays) {
        return durationMinutes[slot] <= rideMinutes && (rideDays & ~reachedDays[slot]) == 0;
    }

    boolean reachedFaster(int slot, int rideMinutes, long rideDays) {
        return durationMinutes[slot] < rideMinutes && (rideDays & ~fastestDays[slot]) == 0;
    }

    void record(int slot, int rideMinutes, long rideDays) {
        reachedDays[slot] |= rideDays;
        if (rideMinutes >= durationMinutes[slot]) return;
        durationMinutes[slot] = rideMinutes;
        fastestDays[slot] = rideDays;
    }

    long sizeBytes() {
        return (long) durationMinutes.length * (Integer.BYTES + 2 * Long.BYTES)
                + (long) tripGeneration.length * 2 * Integer.BYTES;
    }

    /** Trips this search reached, for its log. */
    long reachedTripCount() {
        return Arrays.stream(tripGeneration).filter(stamp -> stamp == generation).count();
    }

    // Grows by half, but never past every event of the store
    private void grow(int minCapacity) {
        int grown = durationMinutes.length + (durationMinutes.length >> 1);
        int capacity = Math.min(eventCount, Math.max(minCapacity, grown));
        durationMinutes = Arrays.copyOf(durationMinutes, capacity);
        fastestDays = Arrays.copyOf(fastestDays, capacity);
        reachedDays = Arrays.copyOf(reachedDays, capacity);
    }
}
