package com.railreach.service;

import com.railreach.model.Connection;
import com.railreach.util.GtfsTime;

import java.util.List;

/**
 * One trip's stops and times, laid out as arrays for the search. Leg i runs from stop i to stop i+1. A leg without
 * a connection has departure {@link CachedTrip#NO_LEG}, and no ride crosses it.
 * Offsets are minutes since the trip's first departure, counted like a ride adds them up: each leg's run time plus
 * the dwell before it, both clamped at 0.
 * {@code index} is the trip's position in {@link SearchTimetables}, {@code stationIndexes} the stops' positions there.
 */
record TripTimetable(int index, String tripId, List<String> stops, int[] stationIndexes, ServiceDays serviceDays,
                     Connection[] legConnections, int[] legDepartureSeconds, int[] legArrivalSeconds,
                     int[] departureOffsetMinutes, int[] arrivalOffsetMinutes) {

    /** A trip leaving a stop; a trip that visits the stop twice has one per visit. */
    record Departure(TripTimetable trip, int stopIdx) {

        int departureSeconds() {
            return trip.legDepartureSeconds[stopIdx];
        }
    }

    /** legConnections[i] is leg i's connection, null where the trip has none. */
    static TripTimetable of(int index, String tripId, List<String> stops, int[] stationIndexes, ServiceDays serviceDays,
                            Connection[] legConnections) {
        int legCount = legConnections.length;
        int[] departures = new int[legCount];
        int[] arrivals = new int[legCount];
        int[] departureOffsets = new int[stops.size()];
        int[] arrivalOffsets = new int[stops.size()];
        int offsetMinutes = 0;
        for (int leg = 0; leg < legCount; leg++) {
            Connection c = legConnections[leg];
            departures[leg] = c != null ? c.getDepartureTimeSeconds() : CachedTrip.NO_LEG;
            if (c != null) {
                arrivals[leg] = c.getArrivalTimeSeconds();
                if (leg > 0 && departures[leg - 1] != CachedTrip.NO_LEG) {
                    offsetMinutes += Math.max(0, GtfsTime.diffMinutes(arrivals[leg - 1], departures[leg]));
                }
            }
            departureOffsets[leg] = offsetMinutes;
            if (c != null) offsetMinutes += Math.max(0, GtfsTime.diffMinutes(departures[leg], arrivals[leg]));
            arrivalOffsets[leg + 1] = offsetMinutes;
        }
        return new TripTimetable(index, tripId, stops, stationIndexes, serviceDays, legConnections, departures, arrivals,
                departureOffsets, arrivalOffsets);
    }

    int stopCount() {
        return stops.size();
    }

    boolean hasLeg(int leg) {
        return legDepartureSeconds[leg] != CachedTrip.NO_LEG;
    }

    int arrivalSeconds(int stopIdx) {
        return legArrivalSeconds[stopIdx - 1];
    }

    /** Minutes from leaving boardIdx to arriving at alightIdx, intermediate dwells included. */
    int rideMinutes(int boardIdx, int alightIdx) {
        return arrivalOffsetMinutes[alightIdx] - departureOffsetMinutes[boardIdx];
    }

    /** Index of the first departure at or after departureSeconds in departures sorted by time. */
    static int firstDepartureAt(List<Departure> departures, int departureSeconds) {
        int low = 0;
        int high = departures.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (departures.get(mid).departureSeconds() < departureSeconds) low = mid + 1;
            else high = mid;
        }
        return low;
    }
}
