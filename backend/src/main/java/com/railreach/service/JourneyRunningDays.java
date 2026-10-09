package com.railreach.service;

import com.railreach.model.Connection;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The days on which a whole journey runs, and their weekday summary for the popup. The search compares days by
 * weekday and period only (B33), so the exact dates are computed here, and only for the journeys it chooses from.
 */
final class JourneyRunningDays {

    // Below this, a weekday pattern would read as regular service
    private static final int MIN_PATTERN_DAYS = 4;
    private static final int MIN_RANGE_LENGTH = 3;

    /** One ride of a journey: boarded on trip tripId at fromStopId, left at toStopId. */
    record RideTimes(String tripId, String fromStopId, int departureSeconds, String toStopId, int arrivalSeconds) {}

    /**
     * The days, counted from the first train's service day, on which every ride so far runs; dayShift is how many
     * days after the first train the last ride runs, arrivalSeconds when it arrives.
     */
    record SoFar(ServiceDays days, int dayShift, int arrivalSeconds) {

        static final SoFar START = new SoFar(ServiceDays.EVERY_DAY, 0, 0);
    }

    private JourneyRunningDays() {
    }

    /** The days, counted from the first train's service day, on which every ride of the journey runs. */
    static ServiceDays of(List<Connection> connections, TransitGraph graph) {
        SoFar soFar = SoFar.START;
        for (RideTimes ride : rides(connections)) {
            soFar = withRide(soFar, ride, graph);
        }
        return soFar.days();
    }

    /** The journey's rides: consecutive connections on the same trip are one ride. */
    static List<RideTimes> rides(List<Connection> connections) {
        List<RideTimes> rides = new ArrayList<>();
        int rideStart = 0;
        while (rideStart < connections.size()) {
            int rideEnd = rideStart;
            while (rideEnd + 1 < connections.size()
                    && connections.get(rideEnd + 1).getTripId().equals(connections.get(rideStart).getTripId())) {
                rideEnd++;
            }
            Connection board = connections.get(rideStart);
            Connection alight = connections.get(rideEnd);
            rides.add(new RideTimes(board.getTripId(), board.getFromStopId(), board.getDepartureTimeSeconds(),
                    alight.getToStopId(), alight.getArrivalTimeSeconds()));
            rideStart = rideEnd + 1;
        }
        return rides;
    }

    /**
     * The journey's days after one more ride. A ride boarded at an earlier clock time than the previous one arrived
     * runs a day later: that is the search's overnight transfer.
     */
    static SoFar withRide(SoFar before, RideTimes ride, TransitGraph graph) {
        int dayShift = ride.departureSeconds() < before.arrivalSeconds() ? before.dayShift() + 1 : before.dayShift();
        ServiceDays rideDays = rideDays(ride, graph);
        // The first train's days from today on set the journey's dates (B31)
        if (before == SoFar.START) rideDays = graph.upcomingDays(rideDays);
        return new SoFar(before.days().intersect(rideDays, dayShift), dayShift, ride.arrivalSeconds());
    }

    /**
     * The days of the ridden trip and of every trip with the same times between the same two stations. Feeds publish
     * one train as several trips with different days (NS: train 9535 as 7 trips, one stop ID apart). The search
     * timetables already merge trips with the same times at every stop, including the ridden one, so their days
     * count too. The ridden trip counts even if it passes the alight station earlier at another time (B37).
     */
    private static ServiceDays rideDays(RideTimes ride, TransitGraph graph) {
        List<TripTimetable.Departure> departures = graph.searchTimetables().departuresAt(ride.fromStopId());
        ServiceDays days = graph.getServiceDays(ride.tripId());
        for (int i = TripTimetable.firstDepartureAt(departures, ride.departureSeconds()); i < departures.size()
                && departures.get(i).departureSeconds() == ride.departureSeconds(); i++) {
            TripTimetable.Departure twin = departures.get(i);
            if (twin.trip().tripId().equals(ride.tripId()) || arrivesLikeRide(twin, ride)) {
                days = days.union(twin.trip().serviceDays());
            }
        }
        return days;
    }

    private static boolean arrivesLikeRide(TripTimetable.Departure departure, RideTimes ride) {
        TripTimetable trip = departure.trip();
        for (int stopIdx = departure.stopIdx() + 1; stopIdx < trip.stopCount() && trip.hasLeg(stopIdx - 1);
             stopIdx++) {
            if (trip.stops().get(stopIdx).equals(ride.toStopId())) {
                return trip.arrivalSeconds(stopIdx) == ride.arrivalSeconds();
            }
        }
        return false;
    }

    /**
     * For example "Runs Mon–Fri", "Runs daily" or "Irregular: 2 days in the timetable". A weekday counts if the
     * journey runs on at least half of its dates between the first and last day, so holidays don't break the pattern.
     * Empty without calendar data or days.
     */
    static Optional<String> describe(ServiceDays days) {
        List<LocalDate> dates = days.epochDays().mapToObj(LocalDate::ofEpochDay).toList();
        if (dates.isEmpty()) return Optional.empty();
        List<DayOfWeek> weekdays = new ArrayList<>();
        if (dates.size() >= MIN_PATTERN_DAYS) {
            for (DayOfWeek weekday : DayOfWeek.values()) {
                long runs = dates.stream().filter(date -> date.getDayOfWeek() == weekday).count();
                long occurrences = dates.getFirst().datesUntil(dates.getLast().plusDays(1))
                        .filter(date -> date.getDayOfWeek() == weekday).count();
                if (runs > 0 && 2 * runs >= occurrences) weekdays.add(weekday);
            }
        }
        if (weekdays.isEmpty()) {
            String dayCount = dates.size() + (dates.size() == 1 ? " day" : " days");
            return Optional.of("Irregular: " + dayCount + " in the timetable");
        }
        return Optional.of("Runs " + weekdayPattern(weekdays));
    }

    /** "daily", "Mon–Fri", "Sat, Sun": runs of three or more consecutive weekdays become a range. */
    private static String weekdayPattern(List<DayOfWeek> weekdays) {
        if (weekdays.size() == DayOfWeek.values().length) return "daily";
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < weekdays.size()) {
            int end = start;
            while (end + 1 < weekdays.size() && weekdays.get(end + 1).getValue() == weekdays.get(end).getValue() + 1) {
                end++;
            }
            if (end - start + 1 >= MIN_RANGE_LENGTH) {
                parts.add(shortName(weekdays.get(start)) + "–" + shortName(weekdays.get(end)));
            } else {
                weekdays.subList(start, end + 1).forEach(weekday -> parts.add(shortName(weekday)));
            }
            start = end + 1;
        }
        return String.join(", ", parts);
    }

    private static String shortName(DayOfWeek weekday) {
        return weekday.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }
}
