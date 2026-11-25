package com.railradius.service;

import java.util.Arrays;

/**
 * Per station what the station search ranks by and {@code /stats} counts: how many stations its trips run to without
 * a stop between, and how many trips stop there. Counted over every trip the feeds hold, past ones included, so a store
 * without past days ({@link PastDays}) keeps the counts of the store it was cut from.
 */
record StationCounts(int[] nextStationCount, int[] stoppingTripCount) {

    private static final int NOT_SEEN = -1;

    /** The counts of the parts' trips. */
    static StationCounts of(TimetableStoreBuilder.Parts parts) {
        int stationCount = parts.stations().size();
        return new StationCounts(nextStationCounts(stationCount, parts.depStart(), parts.depEvent(), parts.evStation()),
                stoppingTripCounts(stationCount, parts.tripFirstEvent(), parts.evStation()));
    }

    private static int[] nextStationCounts(int stationCount, int[] depStart, int[] depEvent, int[] evStation) {
        int[] counts = new int[stationCount];
        // The station that last counted each next station, so each counts once per station
        int[] countedBy = new int[stationCount];
        Arrays.fill(countedBy, NOT_SEEN);
        for (int station = 0; station < stationCount; station++) {
            for (int position = depStart[station]; position < depStart[station + 1]; position++) {
                int next = evStation[depEvent[position] + 1];
                if (countedBy[next] == station) continue;
                countedBy[next] = station;
                counts[station]++;
            }
        }
        return counts;
    }

    private static int[] stoppingTripCounts(int stationCount, int[] tripFirstEvent, int[] evStation) {
        int[] counts = new int[stationCount];
        // The trip that last counted each station, so a trip that stops there twice counts once
        int[] countedBy = new int[stationCount];
        Arrays.fill(countedBy, NOT_SEEN);
        for (int trip = 0; trip + 1 < tripFirstEvent.length; trip++) {
            for (int event = tripFirstEvent[trip]; event < tripFirstEvent[trip + 1]; event++) {
                int station = evStation[event];
                if (countedBy[station] == trip) continue;
                countedBy[station] = trip;
                counts[station]++;
            }
        }
        return counts;
    }
}
