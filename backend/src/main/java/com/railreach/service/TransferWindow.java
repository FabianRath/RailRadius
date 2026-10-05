package com.railreach.service;

import com.railreach.util.GtfsTime;

import java.util.function.IntPredicate;

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

    /**
     * Index of the best arrival for the departure that passes {@code usable}, or -1. Departure times must not
     * decrease between calls.
     */
    int bestFor(int departureSeconds, IntPredicate usable) {
        while (nextArrival < arrivalSeconds.length
                && arrivalSeconds[nextArrival] <= departureSeconds - minWaitSeconds) {
            while (tail > head && startSeconds[queue[tail - 1]] <= startSeconds[nextArrival]) tail--;
            queue[tail++] = nextArrival++;
        }
        while (tail > head && arrivalSeconds[queue[head]] < departureSeconds - maxWaitSeconds) head++;
        if (tail == head) return -1;
        return usable.test(queue[head]) ? queue[head] : bestUsableScan(departureSeconds, usable);
    }

    // The queue has dropped the arrivals that the best one outranks, so if the best is not usable, all arrivals in
    // the wait range are scanned. Usually the best one is usable.
    private int bestUsableScan(int departureSeconds, IntPredicate usable) {
        int best = -1;
        for (int i = firstArrivalFrom(departureSeconds - maxWaitSeconds); i < nextArrival; i++) {
            if ((best < 0 || startSeconds[i] > startSeconds[best]) && usable.test(i)) best = i;
        }
        return best;
    }

    /** Index of the first arrival at or after the time, among those already added to the window. */
    private int firstArrivalFrom(int seconds) {
        int low = 0;
        int high = nextArrival;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (arrivalSeconds[mid] < seconds) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
