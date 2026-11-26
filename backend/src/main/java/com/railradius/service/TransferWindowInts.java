package com.railradius.service;

/**
 * Sliding window over one station's {@link Arrivals} for departures fed in time order: among the arrivals whose wait
 * for the departure lies in the min/max wait and that add a day the departure needs, the one with the latest journey
 * start. Boarding from it gives the shortest journey. The window keeps its queue in a buffer as large as the
 * arrivals. Arrival times count shiftSeconds later, so waits past midnight can use their own window over the same
 * arrivals; start times are only compared with each other, so they need no shift.
 * <p>
 * When the window's best arrival adds no day, a fallback scan looks through the wait range. It took most of a large
 * search, so it looks only where its answer can be (#39): the queue holds arrivals by strictly falling start, and
 * each arrival left out of it was outranked by a later queued one. So no arrival after the first queued one that adds
 * days starts at least as late as it, and the scan stops there. It also skips whole blocks of arrivals that cannot
 * hold its answer: a block none of whose arrivals adds a day, or whose latest start doesn't reach the best one so far.
 */
final class TransferWindowInts {

    private static final int BLOCK_SHIFT = 5;
    private static final int BLOCK_SIZE = 1 << BLOCK_SHIFT;
    private static final int NONE = -1;

    /**
     * A station's arrivals sorted by arrival time: arrival seconds, journey start seconds and {@link WeekPeriodDays}.
     * Per whole block of {@link #BLOCK_SIZE} arrivals: the latest start and all days of its arrivals.
     */
    record Arrivals(int[] arrivalSeconds, int[] startSeconds, long[] days, int[] blockLatestStart, long[] blockDays) {

        static Arrivals ofCapacity(int count) {
            int blocks = count >>> BLOCK_SHIFT;
            return new Arrivals(new int[count], new int[count], new long[count], new int[blocks], new long[blocks]);
        }

        /** Sums up the blocks, once the arrivals are filled in. */
        void summarizeBlocks() {
            for (int block = 0; block < blockDays.length; block++) {
                int latestStart = Integer.MIN_VALUE;
                long blockDaysUnion = 0;
                for (int position = block << BLOCK_SHIFT; position < (block + 1) << BLOCK_SHIFT; position++) {
                    latestStart = Math.max(latestStart, startSeconds[position]);
                    blockDaysUnion |= days[position];
                }
                blockLatestStart[block] = latestStart;
                blockDays[block] = blockDaysUnion;
            }
        }
    }

    private final Arrivals arrivals;
    private final int[] arrivalSeconds;
    private final int[] startSeconds;
    private final int[] queue;
    private final int shiftSeconds;
    private final int minWaitSeconds;
    private final int maxWaitSeconds;
    private int head;
    private int tail;
    private int nextArrival;
    private int fallbackScanCount;
    private long fallbackScannedArrivals;

    TransferWindowInts(Arrivals arrivals, int[] queue, int shiftSeconds, int minWaitSeconds, int maxWaitSeconds) {
        this.arrivals = arrivals;
        this.arrivalSeconds = arrivals.arrivalSeconds();
        this.startSeconds = arrivals.startSeconds();
        this.queue = queue;
        this.shiftSeconds = shiftSeconds;
        this.minWaitSeconds = minWaitSeconds;
        this.maxWaitSeconds = maxWaitSeconds;
    }

    /**
     * Position of the best arrival for the departure whose journey, moved extraDays forward, runs on a day of the
     * departing trip's nextTripDays that served lacks; -1 if none. Departure times must not decrease between calls.
     */
    int bestFor(int departureSeconds, long nextTripDays, int extraDays, long served) {
        int latestArrival = departureSeconds - minWaitSeconds - shiftSeconds;
        while (nextArrival < arrivalSeconds.length && arrivalSeconds[nextArrival] <= latestArrival) {
            while (tail > head && startSeconds[queue[tail - 1]] <= startSeconds[nextArrival]) tail--;
            queue[tail++] = nextArrival++;
        }
        int earliestArrival = departureSeconds - maxWaitSeconds - shiftSeconds;
        while (tail > head && arrivalSeconds[queue[head]] < earliestArrival) head++;
        if (tail == head) return -1;
        long wanted = nextTripDays & ~served;
        if (addsDays(arrivals.days()[queue[head]], extraDays, wanted)) return queue[head];
        return bestUsableScan(earliestArrival, extraDays, wanted);
    }

    int fallbackScanCount() {
        return fallbackScanCount;
    }

    long fallbackScannedArrivals() {
        return fallbackScannedArrivals;
    }

    private static boolean addsDays(long arrivalDays, int extraDays, long wanted) {
        return (WeekPeriodDays.movedForward(arrivalDays, extraDays) & wanted) != 0;
    }

    /**
     * The arrival in range with the latest start that adds days, the first of equal ones. Only arrivals before the
     * first queued one that adds days can start as late as it, so they are scanned for one that does; if none does,
     * that queued one is the answer.
     */
    private int bestUsableScan(int earliestArrival, int extraDays, long wanted) {
        int bound = firstQueuedAddingDays(extraDays, wanted);
        int end = bound == NONE ? nextArrival : bound;
        int first = firstArrivalFrom(earliestArrival);
        fallbackScanCount++;
        fallbackScannedArrivals += end - first;
        // An arrival must start at least this late to win: the first of equal starts wins
        int leastStart = bound == NONE ? Integer.MIN_VALUE : startSeconds[bound];
        int best = NONE;
        int position = first;
        while (position < end) {
            int blockEnd = Math.min(((position >>> BLOCK_SHIFT) + 1) << BLOCK_SHIFT, end);
            // A whole block before end lies within the arrivals, so it is summed up
            if ((position & (BLOCK_SIZE - 1)) == 0 && blockEnd - position == BLOCK_SIZE
                    && cannotWin(position >>> BLOCK_SHIFT, leastStart, extraDays, wanted)) {
                position = blockEnd;
                continue;
            }
            for (; position < blockEnd; position++) {
                if (startSeconds[position] >= leastStart && addsDays(arrivals.days()[position], extraDays, wanted)) {
                    best = position;
                    leastStart = startSeconds[position] + 1;
                }
            }
        }
        return best == NONE ? bound : best;
    }

    /** The first queued arrival after the head that adds days; NONE if none does. */
    private int firstQueuedAddingDays(int extraDays, long wanted) {
        for (int i = head + 1; i < tail; i++) {
            if (addsDays(arrivals.days()[queue[i]], extraDays, wanted)) return queue[i];
        }
        return NONE;
    }

    private boolean cannotWin(int block, int leastStart, int extraDays, long wanted) {
        if (arrivals.blockLatestStart()[block] < leastStart) return true;
        return !addsDays(arrivals.blockDays()[block], extraDays, wanted);
    }

    /** Position of the first arrival at or after the (unshifted) time, among those already added to the window. */
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
