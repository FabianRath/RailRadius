package com.railreach.service;

import java.util.Arrays;

/**
 * {@link TripProgress} of every trip at once, by stop event of the {@link TimetableStore}, reused from search to
 * search. A trip's events are reset when a search first reaches it, marked by stamping the trip with the search's
 * generation, so starting a search costs nothing.
 */
final class TripProgressFlat {

    private final int[] durationMinutes;
    private final long[] fastestDays;
    private final long[] reachedDays;
    private final int[] tripGeneration;
    private int generation;

    TripProgressFlat(TimetableStore store) {
        durationMinutes = new int[store.eventCount()];
        fastestDays = new long[store.eventCount()];
        reachedDays = new long[store.eventCount()];
        tripGeneration = new int[store.tripCount()];
    }

    void startSearch() {
        generation++;
        // After 2^32 searches a stamp could match again
        if (generation == 0) {
            Arrays.fill(tripGeneration, 0);
            generation = 1;
        }
    }

    /** Makes the trip's events, firstEvent up to endEvent, unreached if this search has not reached the trip yet. */
    void reach(int trip, int firstEvent, int endEvent) {
        if (tripGeneration[trip] == generation) return;
        tripGeneration[trip] = generation;
        Arrays.fill(durationMinutes, firstEvent, endEvent, Integer.MAX_VALUE);
        Arrays.fill(fastestDays, firstEvent, endEvent, 0L);
        Arrays.fill(reachedDays, firstEvent, endEvent, 0L);
    }

    boolean reachedNoSlower(int event, int rideMinutes, long rideDays) {
        return durationMinutes[event] <= rideMinutes && (rideDays & ~reachedDays[event]) == 0;
    }

    boolean reachedFaster(int event, int rideMinutes, long rideDays) {
        return durationMinutes[event] < rideMinutes && (rideDays & ~fastestDays[event]) == 0;
    }

    void record(int event, int rideMinutes, long rideDays) {
        reachedDays[event] |= rideDays;
        if (rideMinutes >= durationMinutes[event]) return;
        durationMinutes[event] = rideMinutes;
        fastestDays[event] = rideDays;
    }

    long sizeBytes() {
        return (long) durationMinutes.length * (Integer.BYTES + 2 * Long.BYTES)
                + (long) tripGeneration.length * Integer.BYTES;
    }

    /** Trips this search reached, for its log. */
    long reachedTripCount() {
        return Arrays.stream(tripGeneration).filter(stamp -> stamp == generation).count();
    }
}
