package com.railreach.service;

import java.util.Arrays;

/**
 * Per stop of a trip: the shortest duration that reached it there, the days of that ride, and the days of every
 * ride recorded there. Rides of one trip arrive at a stop at the same time and catch the same trains from there,
 * so a ride is cut off where it is no faster and all its days are already reached. On some of those days the
 * journey shown can then be a bit slower than the fastest possible.
 */
final class TripProgress {

    private final int[] durationMinutes;
    private final long[] fastestDays;
    private final long[] reachedDays;

    TripProgress(int stopCount) {
        durationMinutes = new int[stopCount];
        Arrays.fill(durationMinutes, Integer.MAX_VALUE);
        fastestDays = new long[stopCount];
        reachedDays = new long[stopCount];
    }

    boolean reachedNoSlower(int stopIdx, int rideMinutes, long rideDays) {
        return durationMinutes[stopIdx] <= rideMinutes && (rideDays & ~reachedDays[stopIdx]) == 0;
    }

    boolean reachedFaster(int stopIdx, int rideMinutes, long rideDays) {
        return durationMinutes[stopIdx] < rideMinutes && (rideDays & ~fastestDays[stopIdx]) == 0;
    }

    void record(int stopIdx, int rideMinutes, long rideDays) {
        reachedDays[stopIdx] |= rideDays;
        if (rideMinutes >= durationMinutes[stopIdx]) return;
        durationMinutes[stopIdx] = rideMinutes;
        fastestDays[stopIdx] = rideDays;
    }
}
