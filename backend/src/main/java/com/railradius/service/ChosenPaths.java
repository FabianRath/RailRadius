package com.railradius.service;

/**
 * The labels of the paths a compact search chose so far, each with its station. The arena's trims keep them and the
 * rides before them, so at the end of the search they become its {@link CompactResult}. Reused like the rest of the
 * {@link SearchScratch}.
 */
final class ChosenPaths {

    /** Per station its chosen labels: positions {@code start[station]} up to {@code start[station + 1]}. */
    record ByStation(int[] start, int[] labels) {

        long sizeBytes() {
            return (long) (start.length + labels.length) * Integer.BYTES;
        }
    }

    private final IntList stations = new IntList();
    private final IntList labels = new IntList();

    void clear() {
        stations.clear();
        labels.clear();
    }

    void add(int station, int label) {
        stations.add(station);
        labels.add(label);
    }

    /** Marks every chosen label, with the rides before it, for the arena's next trim. */
    void keep(LabelArena arena) {
        for (int i = 0; i < labels.size(); i++) {
            arena.keepWithEarlierRides(labels.get(i));
        }
    }

    /** After the trim: moves the chosen labels to their new indexes. */
    void remap(LabelArena arena) {
        for (int i = 0; i < labels.size(); i++) {
            labels.set(i, arena.trimmedIndex(labels.get(i)));
        }
    }

    /** Each station's labels keep the order they were chosen in, which is by ascending transfers. */
    ByStation byStation(int stationCount) {
        int[] start = new int[stationCount + 1];
        for (int i = 0; i < stations.size(); i++) {
            start[stations.get(i) + 1]++;
        }
        for (int station = 0; station < stationCount; station++) {
            start[station + 1] += start[station];
        }
        int[] byStation = new int[labels.size()];
        int[] next = start.clone();
        for (int i = 0; i < labels.size(); i++) {
            byStation[next[stations.get(i)]++] = labels.get(i);
        }
        return new ByStation(start, byStation);
    }

    long sizeBytes() {
        return stations.sizeBytes() + labels.sizeBytes();
    }
}
