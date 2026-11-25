package com.railradius.service;

/**
 * One thread's working arrays for a station choosing what to board ({@link CompactTransferRound}): its sorted arrivals,
 * their grouped positions, the two transfer windows' queues and each arrival's index into the boardings. A station
 * used to allocate them anew, ~32 bytes per arrival and ~190 MB per round on the full data (#40). A thread chooses one
 * station at a time, so it reuses them from station to station and search to search, growing them for a station with
 * more arrivals than any before. Each station writes every entry it reads, up to its arrival count.
 */
final class BoardingWorkspace {

    private static final ThreadLocal<BoardingWorkspace> OF_THREAD = ThreadLocal.withInitial(BoardingWorkspace::new);

    private int capacity;
    private int[] arrivalSeconds = new int[0];
    private int[] startSeconds = new int[0];
    private long[] days = new long[0];
    private int[] blockLatestStart = new int[0];
    private long[] blockDays = new long[0];
    private int[] positions = new int[0];
    private int[] sameDayQueue = new int[0];
    private int[] overnightQueue = new int[0];
    private int[] fromIndex = new int[0];

    private BoardingWorkspace() {
    }

    /** This thread's workspace, with room for count arrivals. */
    static BoardingWorkspace ofThisThread(int count) {
        BoardingWorkspace workspace = OF_THREAD.get();
        workspace.ensureCapacity(count);
        return workspace;
    }

    /** The arrival columns, as a station's count arrivals. */
    TransferWindowInts.Arrivals arrivals(int count) {
        return new TransferWindowInts.Arrivals(count, arrivalSeconds, startSeconds, days, blockLatestStart, blockDays);
    }

    int[] positions() {
        return positions;
    }

    int[] sameDayQueue() {
        return sameDayQueue;
    }

    int[] overnightQueue() {
        return overnightQueue;
    }

    int[] fromIndex() {
        return fromIndex;
    }

    /** Growing replaces the arrays without copying: a station writes before it reads. */
    private void ensureCapacity(int count) {
        if (count <= capacity) return;
        capacity = Math.max(count, capacity * 2);
        arrivalSeconds = new int[capacity];
        startSeconds = new int[capacity];
        days = new long[capacity];
        blockLatestStart = new int[TransferWindowInts.Arrivals.blockCount(capacity)];
        blockDays = new long[TransferWindowInts.Arrivals.blockCount(capacity)];
        positions = new int[capacity];
        sameDayQueue = new int[capacity];
        overnightQueue = new int[capacity];
        fromIndex = new int[capacity];
    }
}
