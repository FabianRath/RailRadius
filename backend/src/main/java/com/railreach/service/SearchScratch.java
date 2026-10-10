package com.railreach.service;

import java.util.Arrays;

/**
 * Everything a compact search writes, sized for one {@link TimetableStore} and kept for the next search, so a search
 * allocates little. There is one per search running at once, which the {@link SearchGate} bounds. rides are the
 * running round's rides, kept for the next round; roundStations the stations that got a ride in the running round,
 * pathMinutes per station the duration of its last chosen path, chosen the paths chosen so far.
 */
record SearchScratch(TimetableStore store, LabelArena labels, TripProgressFlat progress, RoundRides rides,
                     StationCandidatesFlat candidates, LabelDays days, CompactTransferRound transferRound,
                     IntList roundStations, int[] pathMinutes, ChosenPaths chosen) {

    static SearchScratch of(TimetableStore store) {
        LabelArena labels = new LabelArena();
        TripProgressFlat progress = new TripProgressFlat(store);
        RoundRides rides = new RoundRides();
        int stationCount = store.stations().size();
        return new SearchScratch(store, labels, progress, rides, new StationCandidatesFlat(stationCount),
                new LabelDays(labels), new CompactTransferRound(store, labels, progress, rides), new IntList(),
                new int[stationCount], new ChosenPaths());
    }

    /** Bytes of the arrays, for the search's log. */
    long sizeBytes() {
        return labels.sizeBytes() + progress.sizeBytes() + rides.sizeBytes() + candidates.sizeBytes()
                + transferRound.sizeBytes() + (long) pathMinutes.length * Integer.BYTES + chosen.sizeBytes();
    }

    void startSearch() {
        labels.clear();
        rides.clear();
        progress.startSearch();
        candidates.clear();
        days.clear();
        roundStations.clear();
        chosen.clear();
        Arrays.fill(pathMinutes, Integer.MAX_VALUE);
    }
}
