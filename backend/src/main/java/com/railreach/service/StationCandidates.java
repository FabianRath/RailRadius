package com.railreach.service;

import java.util.ArrayList;
import java.util.List;

/**
 * The candidate rides of a station's result, by ascending transfers: per transfer count the fastest one and those
 * at most {@link #MAX_SLOWER_MINUTES} slower, all faster than every ride with fewer transfers. Rounds run by
 * ascending transfers, so rides arrive in that order.
 */
final class StationCandidates {

    // A journey this much slower than the fastest can still be shown for a station, if it runs more often
    static final int MAX_SLOWER_MINUTES = 30;

    final String stationId;
    final List<Ride> rides = new ArrayList<>();
    int transfers = -1;
    private int fastestWithFewerMinutes = Integer.MAX_VALUE;
    private int fastestMinutes = Integer.MAX_VALUE;

    StationCandidates(String stationId) {
        this.stationId = stationId;
    }

    void offer(Ride ride) {
        if (ride.transfers() != transfers) {
            fastestWithFewerMinutes = Math.min(fastestWithFewerMinutes, fastestMinutes);
            fastestMinutes = Integer.MAX_VALUE;
            transfers = ride.transfers();
        }
        int durationMinutes = ride.durationMinutes();
        if (durationMinutes >= fastestWithFewerMinutes) return;
        if (durationMinutes - MAX_SLOWER_MINUTES > fastestMinutes) return;
        if (durationMinutes < fastestMinutes) {
            fastestMinutes = durationMinutes;
            rides.removeIf(kept -> kept.transfers() == transfers
                    && kept.durationMinutes() - MAX_SLOWER_MINUTES > durationMinutes);
        }
        rides.add(ride);
    }
}
