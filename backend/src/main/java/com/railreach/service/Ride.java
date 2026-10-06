package com.railreach.service;

/**
 * One ride from boardIdx to alightIdx; previous is the ride before the transfer, null for the first one. days are
 * the {@link WeekPeriodDays} of the trip on which the journey so far works: every earlier train runs on its day.
 */
record Ride(TripTimetable trip, int boardIdx, int alightIdx, int transfers, int durationMinutes, Ride previous,
            long days) {

    /** The days of a next trip, running on nextTripDays, that come extraDays after a day of this ride. */
    long nextDays(long nextTripDays, int extraDays) {
        return nextTripDays & WeekPeriodDays.movedForward(days, extraDays);
    }

    String stationId() {
        return trip.stops().get(alightIdx);
    }

    int stationIndex() {
        return trip.stationIndexes()[alightIdx];
    }

    int arrivalSeconds() {
        return trip.arrivalSeconds(alightIdx);
    }

    JourneyRunningDays.RideTimes times() {
        return new JourneyRunningDays.RideTimes(trip.tripId(), trip.stops().get(boardIdx),
                trip.legDepartureSeconds()[boardIdx], stationId(), arrivalSeconds());
    }
}
