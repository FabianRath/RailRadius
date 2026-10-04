package com.railreach.service;

import com.railreach.util.GtfsTime;

/**
 * Sliding window over one station's arrivals for departures fed in time order: among the arrivals whose wait for
 * the departure lies in [minWaitSeconds, maxWaitSeconds], the one with the latest journey start. Boarding from it
 * gives the shortest journey. Arrival times are shifted by shiftSeconds first, so waits past midnight can use their
 * own window over the same arrivals.
 */
final class TransferWindow {

    private final int[] arrivalSeconds;
    private final int[] startSeconds;
    private final int minWaitSeconds;
    private final int maxWaitSeconds;
    // Indexes of the arrivals still in the window, by decreasing start time
    private final int[] queue;
    private int head;
    private int tail;
    private int nextArrival;

    /** arrivalSeconds must be ascending; durationMinutes[i] is the journey time up to arrival i. */
    TransferWindow(int[] arrivalSeconds, int[] durationMinutes, int shiftSeconds,
                   int minWaitSeconds, int maxWaitSeconds) {
        this.arrivalSeconds = new int[arrivalSeconds.length];
        this.startSeconds = new int[arrivalSeconds.length];
        for (int i = 0; i < arrivalSeconds.length; i++) {
            this.arrivalSeconds[i] = arrivalSeconds[i] + shiftSeconds;
            this.startSeconds[i] = this.arrivalSeconds[i] - durationMinutes[i] * GtfsTime.SECONDS_PER_MINUTE;
        }
        this.minWaitSeconds = minWaitSeconds;
        this.maxWaitSeconds = maxWaitSeconds;
        this.queue = new int[arrivalSeconds.length];
    }

    /** Index of the best arrival for the departure, or -1. Departure times must not decrease between calls. */
    int bestFor(int departureSeconds) {
        while (nextArrival < arrivalSeconds.length
                && arrivalSeconds[nextArrival] <= departureSeconds - minWaitSeconds) {
            while (tail > head && startSeconds[queue[tail - 1]] <= startSeconds[nextArrival]) tail--;
            queue[tail++] = nextArrival++;
        }
        while (tail > head && arrivalSeconds[queue[head]] < departureSeconds - maxWaitSeconds) head++;
        return tail > head ? queue[head] : -1;
    }
}
