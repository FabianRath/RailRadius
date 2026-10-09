package com.railreach.service;

import java.util.Arrays;

/**
 * {@link StationCandidates} of every station at once, reused from search to search. A station's candidates are a
 * list through the labels (each label's next one), in the order they were offered, and removing slower ones keeps
 * that order like {@code removeIf} does. Only the newest transfer count's candidates are ever read, so the list
 * starts empty when the count goes up.
 */
final class StationCandidatesFlat {

    private static final int NEVER_REACHED = -1;

    private final int[] transfers;
    private final int[] fastestWithFewerMinutes;
    private final int[] fastestMinutes;
    private final int[] head;
    private final int[] tail;
    private int[] next = new int[0];
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
        head[station] = LabelArena.NONE;
        tail[station] = LabelArena.NONE;
        return true;
    }

    /** Whether a ride this long becomes a candidate of the station. */
    boolean accepts(int station, int minutes) {
        return minutes < fastestWithFewerMinutes[station]
                && minutes - StationCandidates.MAX_SLOWER_MINUTES <= fastestMinutes[station];
    }

    /** Adds an accepted ride, dropping the candidates it makes too slow. */
    void add(int station, int label, LabelArena labels) {
        int minutes = labels.durationMinutes(label);
        if (minutes < fastestMinutes[station]) {
            fastestMinutes[station] = minutes;
            removeSlowerThan(station, minutes + StationCandidates.MAX_SLOWER_MINUTES, labels);
        }
        append(station, label);
    }

    /** The station's first candidate of its newest transfer count, {@link LabelArena#NONE} if none. */
    int first(int station) {
        return head[station];
    }

    /** The candidate after this one, {@link LabelArena#NONE} at the end. */
    int next(int label) {
        return next[label];
    }

    long sizeBytes() {
        return (long) transfers.length * 5 * Integer.BYTES + (long) next.length * Integer.BYTES;
    }

    int count(int station) {
        int count = 0;
        for (int label = head[station]; label != LabelArena.NONE; label = next[label]) {
            count++;
        }
        return count;
    }

    private void append(int station, int label) {
        if (label >= next.length) next = Arrays.copyOf(next, Math.max(label + 1, next.length + (next.length >> 1)));
        next[label] = LabelArena.NONE;
        if (tail[station] == LabelArena.NONE) {
            head[station] = label;
        } else {
            next[tail[station]] = label;
        }
        tail[station] = label;
    }

    private void removeSlowerThan(int station, int maxMinutes, LabelArena labels) {
        int kept = LabelArena.NONE;
        for (int label = head[station]; label != LabelArena.NONE; label = next[label]) {
            if (labels.durationMinutes(label) > maxMinutes) continue;
            if (kept == LabelArena.NONE) {
                head[station] = label;
            } else {
                next[kept] = label;
            }
            kept = label;
        }
        if (kept == LabelArena.NONE) {
            head[station] = LabelArena.NONE;
        } else {
            next[kept] = LabelArena.NONE;
        }
        tail[station] = kept;
    }
}
