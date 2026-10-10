package com.railreach.service;

import java.util.Arrays;

/**
 * The candidate rides of every station's result, reused from search to search: per station and transfer count the
 * fastest one and those at most {@link #MAX_SLOWER_MINUTES} slower, all faster than every ride with fewer transfers.
 * Rounds run by ascending transfers, so rides arrive in that order. A station's candidates are a list through slots
 * of a pool, in the order they were offered, and removing slower ones keeps that order like
 * {@code removeIf} does. Only the newest transfer count's candidates are ever read, so the list starts empty when the
 * count goes up, and the pool is emptied after each round: a round has far fewer candidates than labels.
 */
final class StationCandidatesFlat {

    // A journey this much slower than the fastest can still be shown for a station, if it runs more often
    static final int MAX_SLOWER_MINUTES = 30;
    private static final int NEVER_REACHED = -1;
    static final int NONE = -1;

    private final int[] transfers;
    private final int[] fastestWithFewerMinutes;
    private final int[] fastestMinutes;
    private final int[] head;
    private final int[] tail;
    // Per slot: its candidate's label, and the next slot of the station's list
    private final IntList slotLabels = new IntList();
    private final IntList slotNext = new IntList();
    private int reachedCount;

    StationCandidatesFlat(int stationCount) {
        transfers = new int[stationCount];
        fastestWithFewerMinutes = new int[stationCount];
        fastestMinutes = new int[stationCount];
        head = new int[stationCount];
        tail = new int[stationCount];
    }

    void clear() {
        Arrays.fill(transfers, NEVER_REACHED);
        Arrays.fill(fastestWithFewerMinutes, Integer.MAX_VALUE);
        Arrays.fill(fastestMinutes, Integer.MAX_VALUE);
        reachedCount = 0;
        endRound();
    }

    /** After the round's paths are chosen: no later round reads its candidates. */
    void endRound() {
        slotLabels.clear();
        slotNext.clear();
    }

    /** Stations that got a candidate in this search. */
    int reachedCount() {
        return reachedCount;
    }

    /**
     * Before offering a ride with this many transfers that arrives at the station: true if it is the station's first
     * one, so the station joins the round's stations. Rides come by ascending transfers. Splitting the offer into
     * this, {@link #accepts} and {@link #add} lets the last round store only rides that become candidates.
     */
    boolean enter(int station, int rideTransfers) {
        if (transfers[station] == rideTransfers) return false;
        if (transfers[station] == NEVER_REACHED) reachedCount++;
        fastestWithFewerMinutes[station] = Math.min(fastestWithFewerMinutes[station], fastestMinutes[station]);
        fastestMinutes[station] = Integer.MAX_VALUE;
        transfers[station] = rideTransfers;
        head[station] = NONE;
        tail[station] = NONE;
        return true;
    }

    /** Whether a ride this long becomes a candidate of the station. */
    boolean accepts(int station, int minutes) {
        return minutes < fastestWithFewerMinutes[station]
                && minutes - MAX_SLOWER_MINUTES <= fastestMinutes[station];
    }

    /** Adds an accepted ride, dropping the candidates it makes too slow. */
    void add(int station, int label, LabelArena labels) {
        int minutes = labels.durationMinutes(label);
        if (minutes < fastestMinutes[station]) {
            fastestMinutes[station] = minutes;
            removeSlowerThan(station, minutes + MAX_SLOWER_MINUTES, labels);
        }
        append(station, label);
    }

    /** The slot of the station's first candidate of its newest transfer count, {@link #NONE} if none. */
    int first(int station) {
        return head[station];
    }

    /** The slot after this one, {@link #NONE} at the end. */
    int next(int slot) {
        return slotNext.get(slot);
    }

    /** The label of the candidate in this slot. */
    int label(int slot) {
        return slotLabels.get(slot);
    }

    long sizeBytes() {
        return (long) transfers.length * 5 * Integer.BYTES + slotLabels.sizeBytes() + slotNext.sizeBytes();
    }

    int count(int station) {
        int count = 0;
        for (int slot = head[station]; slot != NONE; slot = next(slot)) {
            count++;
        }
        return count;
    }

    private void append(int station, int label) {
        int slot = slotLabels.size();
        slotLabels.add(label);
        slotNext.add(NONE);
        if (tail[station] == NONE) {
            head[station] = slot;
        } else {
            slotNext.set(tail[station], slot);
        }
        tail[station] = slot;
    }

    private void removeSlowerThan(int station, int maxMinutes, LabelArena labels) {
        int kept = NONE;
        for (int slot = head[station]; slot != NONE; slot = next(slot)) {
            if (labels.durationMinutes(label(slot)) > maxMinutes) continue;
            if (kept == NONE) {
                head[station] = slot;
            } else {
                slotNext.set(kept, slot);
            }
            kept = slot;
        }
        if (kept == NONE) {
            head[station] = NONE;
        } else {
            slotNext.set(kept, NONE);
        }
        tail[station] = kept;
    }
}
