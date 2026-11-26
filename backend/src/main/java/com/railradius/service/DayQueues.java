package com.railradius.service;

import java.util.Arrays;

/**
 * One queue per day bit over a window's arrivals ({@link TransferWindowInts}): those in range that run on that day,
 * with their days moved extraDays forward, by falling start, the first of equal starts ahead. The arrival with the
 * latest start that runs on one of some days, the first of equal ones, is then the best of those days' queue heads:
 * one read per wanted day instead of a scan of the wait range (#82). Journey starts mostly rise with arrival time, so
 * the queues stay short. Arrivals are queued only when asked for, so those that left the range in between never are.
 */
final class DayQueues {

    private static final int INITIAL_CAPACITY = 8;
    private static final int NONE = -1;

    private final TransferWindowInts.Arrivals arrivals;
    private final int[] arrivalSeconds;
    private final int[] startSeconds;
    private final int extraDays;
    private final int[][] queues = new int[Long.SIZE][];
    private final int[] heads = new int[Long.SIZE];
    private final int[] tails = new int[Long.SIZE];
    private int nextArrival;

    DayQueues(TransferWindowInts.Arrivals arrivals, int extraDays) {
        this.arrivals = arrivals;
        this.arrivalSeconds = arrivals.arrivalSeconds();
        this.startSeconds = arrivals.startSeconds();
        this.extraDays = extraDays;
    }

    /**
     * Position of the arrival before endArrival and from the (unshifted) earliest arrival time on with the latest
     * start that runs on one of the days, the first of equal ones; NONE if none does. Neither may decrease between
     * calls.
     */
    int best(int earliestArrival, int endArrival, long days) {
        queueUpTo(earliestArrival, endArrival);
        int best = NONE;
        int bestStart = Integer.MIN_VALUE;
        for (long rest = days; rest != 0; rest &= rest - 1) {
            int day = Long.numberOfTrailingZeros(rest);
            int[] queue = queues[day];
            if (queue == null) continue;
            int head = heads[day];
            int tail = tails[day];
            while (head < tail && arrivalSeconds[queue[head]] < earliestArrival) head++;
            heads[day] = head;
            if (head == tail) continue;
            int position = queue[head];
            int start = startSeconds[position];
            if (start > bestStart || (start == bestStart && position < best)) {
                best = position;
                bestStart = start;
            }
        }
        return best;
    }

    /** Queues the arrivals before endArrival not yet queued, skipping those already out of range. */
    private void queueUpTo(int earliestArrival, int endArrival) {
        while (nextArrival < endArrival && arrivalSeconds[nextArrival] < earliestArrival) nextArrival++;
        for (; nextArrival < endArrival; nextArrival++) {
            long arrivalDays = WeekPeriodDays.movedForward(arrivals.days()[nextArrival], extraDays);
            for (long rest = arrivalDays; rest != 0; rest &= rest - 1) {
                push(Long.numberOfTrailingZeros(rest), nextArrival);
            }
        }
    }

    /** Equal starts stay queued: the earlier one wins while in range, the later one after it has left. */
    private void push(int day, int position) {
        int[] queue = queues[day];
        if (queue == null) {
            queue = new int[INITIAL_CAPACITY];
            queues[day] = queue;
        }
        int head = heads[day];
        int tail = tails[day];
        int start = startSeconds[position];
        while (tail > head && startSeconds[queue[tail - 1]] < start) tail--;
        if (tail == queue.length) {
            System.arraycopy(queue, head, queue, 0, tail - head);
            tail -= head;
            heads[day] = 0;
            if (tail == queue.length) {
                queue = Arrays.copyOf(queue, queue.length * 2);
                queues[day] = queue;
            }
        }
        queue[tail++] = position;
        tails[day] = tail;
    }
}
