package com.railradius.service;

/**
 * How a search's scratch arrays grow. A scratch is kept for the next search and never shrinks, so spare room is held
 * for good, while growing again happens only until the scratch has seen the largest search. So arrays grow by an
 * eighth instead of by half.
 */
final class ScratchCapacity {

    private static final int GROWTH_SHIFT = 3;

    private ScratchCapacity() {
    }

    /** The capacity to grow to: an eighth more, and at least minCapacity. */
    static int grown(int capacity, int minCapacity) {
        return Math.max(minCapacity, capacity + (capacity >> GROWTH_SHIFT));
    }
}
