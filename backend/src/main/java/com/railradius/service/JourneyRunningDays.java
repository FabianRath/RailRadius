package com.railradius.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.LongStream;

/**
 * The days on which a whole journey runs, and their weekday summary for the popup. The search compares days by
 * weekday and period only (B33), so the exact dates are computed here, and only for the journeys it chooses from.
 */
final class JourneyRunningDays {

    // Below this, a weekday pattern would read as regular service
    private static final int MIN_PATTERN_DAYS = 4;
    private static final int MIN_RANGE_LENGTH = 3;

    /**
     * The days, counted from the first train's service day, on which every ride so far runs; dayShift is how many
     * days after the first train the last ride runs, arrivalSeconds when it arrives.
     */
    record SoFar(ServiceDays days, int dayShift, int arrivalSeconds) {

        static final SoFar START = new SoFar(ServiceDays.EVERY_DAY, 0, 0);
    }

    private JourneyRunningDays() {
    }

    /**
     * The journey's days after one more ride, from boardEvent to alightEvent of a trip. A ride boarded at an earlier
     * clock time than the previous one arrived runs a day later: that is the search's overnight transfer. The ride's
     * days are {@link TimetableStore#rideDays}: those of its trip and its twins.
     */
    static SoFar withRide(SoFar before, TimetableStore store, int boardEvent, int alightEvent, TransitGraph graph) {
        int dayShift = store.departureSeconds(boardEvent) < before.arrivalSeconds()
                ? before.dayShift() + 1 : before.dayShift();
        ServiceDays rideDays = store.rideDays(boardEvent, alightEvent);
        // The first train's days from today on set the journey's dates (B31)
        ServiceDays days = before == SoFar.START ? graph.upcomingDays(rideDays) : rideDays;
        return new SoFar(before.days().intersect(days, dayShift), dayShift, store.arrivalSeconds(alightEvent));
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
                long occurrences = LongStream.rangeClosed(dates.getFirst().toEpochDay(), dates.getLast().toEpochDay())
                        .mapToObj(LocalDate::ofEpochDay)
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
