package com.railradius.service;

/**
 * Sliding window over one station's {@link Arrivals} for departures fed in time order: among the arrivals whose wait
 * for the departure lies in the min/max wait and that add a day the departure needs, the one with the latest journey
 * start. Boarding from it gives the shortest journey. The window keeps its queue in a buffer at least as large as
 * the arrivals. Arrival times count shiftSeconds later, so waits past midnight can use their own window over the same
 * arrivals; start times are only compared with each other, so they need no shift.
 * <p>
 * When the window's best arrival adds no day, a fallback scan looks through the wait range. It takes most of a large
 * search, so it skips whole blocks of arrivals that cannot hold its answer: a block none of whose arrivals adds a
 * day, or whose latest start doesn't beat the best one so far.
 */
final class TransferWindowInts {

    private static final int BLOCK_SHIFT = 5;
    private static final int BLOCK_SIZE = 1 << BLOCK_SHIFT;

    /**
     * A station's count arrivals sorted by arrival time: arrival seconds, journey start seconds and
     * {@link WeekPeriodDays}. Per whole block of {@link #BLOCK_SIZE} arrivals: the latest start and all days of its
     * arrivals. The arrays may be longer than count ({@link BoardingWorkspace}); entries past it mean nothing.
     */
    record Arrivals(int count, int[] arrivalSeconds, int[] startSeconds, long[] days, int[] blockLatestStart,
                    long[] blockDays) {

        /** Whole blocks among count arrivals. */
        static int blockCount(int count) {
            return count >>> BLOCK_SHIFT;
        }

        /** Sums up the blocks, once the arrivals are filled in. */
        void summarizeBlocks() {
            for (int block = 0; block < blockCount(count); block++) {
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
    private final int count;
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
        this.count = arrivals.count();
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
        while (nextArrival < count && arrivalSeconds[nextArrival] <= latestArrival) {
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
     * The queue has dropped what its best arrival outranks, so the arrivals in range are
     * scanned for the one with the latest start that adds days, the first of equal ones.
     */
    private int bestUsableScan(int earliestArrival, int extraDays, long wanted) {
        int first = firstArrivalFrom(earliestArrival);
        fallbackScanCount++;
        fallbackScannedArrivals += nextArrival - first;
        int best = -1;
        int position = first;
        while (position < nextArrival) {
            int blockEnd = Math.min(((position >>> BLOCK_SHIFT) + 1) << BLOCK_SHIFT, nextArrival);
            // A whole block before nextArrival lies within the arrivals, so it is summed up
            if ((position & (BLOCK_SIZE - 1)) == 0 && blockEnd - position == BLOCK_SIZE
                    && cannotBeat(position >>> BLOCK_SHIFT, best, extraDays, wanted)) {
                position = blockEnd;
                continue;
            }
            for (; position < blockEnd; position++) {
                if ((best < 0 || startSeconds[position] > startSeconds[best])
                        && addsDays(arrivals.days()[position], extraDays, wanted)) {
                    best = position;
                }
            }
        }
        return best;
    }

    private boolean cannotBeat(int block, int best, int extraDays, long wanted) {
        if (best >= 0 && arrivals.blockLatestStart()[block] <= startSeconds[best]) return true;
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
