package com.railradius.service;

import com.railradius.util.GtfsTime;

/**
 * Sliding window over one station's {@link Arrivals} for departures fed in time order: among the arrivals whose wait
 * for the departure lies in the min/max wait and that add a day the departure needs, the one with the latest journey
 * start. Boarding from it gives the shortest journey. The window keeps its queue in a buffer as large as the
 * arrivals. A window for waits past midnight (extraDays 1) counts arrival times a day later and moves their days a
 * day forward, over the same arrivals; start times are only compared with each other, so they need no shift.
 * <p>
 * When the queue's best arrival adds no day, the fallback answers from {@link DayQueues}: the latest start among the
 * arrivals in range that run on a wanted day, the first of equal ones. It used to scan the wait range, which was most
 * of a large search's time on the full data, chiefly because a departure asks again for the days its earlier
 * answers left out (#82). A window makes its day queues at its first fallback; most windows never need one.
 */
final class TransferWindowInts {

    /** A station's arrivals sorted by arrival time: arrival seconds, journey start seconds and {@link WeekPeriodDays}. */
    record Arrivals(int[] arrivalSeconds, int[] startSeconds, long[] days) {

        static Arrivals ofCapacity(int count) {
            return new Arrivals(new int[count], new int[count], new long[count]);
        }
    }

    private final Arrivals arrivals;
    private final int[] arrivalSeconds;
    private final int[] startSeconds;
    private final int[] queue;
    private final int extraDays;
    private final int shiftSeconds;
    private final int minWaitSeconds;
    private final int maxWaitSeconds;
    private int head;
    private int tail;
    private int nextArrival;
    private int fallbackCount;
    private DayQueues dayQueues;

    TransferWindowInts(Arrivals arrivals, int[] queue, int extraDays, int minWaitSeconds, int maxWaitSeconds) {
        this.arrivals = arrivals;
        this.arrivalSeconds = arrivals.arrivalSeconds();
        this.startSeconds = arrivals.startSeconds();
        this.queue = queue;
        this.extraDays = extraDays;
        this.shiftSeconds = -extraDays * GtfsTime.SECONDS_PER_DAY;
        this.minWaitSeconds = minWaitSeconds;
        this.maxWaitSeconds = maxWaitSeconds;
    }

    /**
     * Position of the best arrival for the departure whose journey, moved the window's extra days forward, runs on a
     * day of the departing trip's nextTripDays that served lacks; -1 if none. Departure times must not decrease
     * between calls.
     */
    int bestFor(int departureSeconds, long nextTripDays, long served) {
        int latestArrival = departureSeconds - minWaitSeconds - shiftSeconds;
        while (nextArrival < arrivalSeconds.length && arrivalSeconds[nextArrival] <= latestArrival) {
            while (tail > head && startSeconds[queue[tail - 1]] <= startSeconds[nextArrival]) tail--;
            queue[tail++] = nextArrival++;
        }
        int earliestArrival = departureSeconds - maxWaitSeconds - shiftSeconds;
        while (tail > head && arrivalSeconds[queue[head]] < earliestArrival) head++;
        if (tail == head) return -1;
        long wanted = nextTripDays & ~served;
        if ((movedDays(queue[head]) & wanted) != 0) return queue[head];
        fallbackCount++;
        if (dayQueues == null) dayQueues = new DayQueues(arrivals, extraDays);
        return dayQueues.best(earliestArrival, nextArrival, wanted);
    }

    int fallbackCount() {
        return fallbackCount;
    }

    private long movedDays(int position) {
        return WeekPeriodDays.movedForward(arrivals.days()[position], extraDays);
    }
}
