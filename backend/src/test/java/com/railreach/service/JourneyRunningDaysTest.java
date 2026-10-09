package com.railreach.service;

import com.railreach.model.Connection;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyRunningDaysTest {

    private static final TripInfo NO_INFO = new TripInfo(null, null, null);
    private static final LocalDate DAY_ONE = LocalDate.of(2026, 1, 1);

    @Test
    void weekdayPatternIgnoresAHoliday() {
        ServiceDays weekdays = days(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 30), date ->
                date.getDayOfWeek().getValue() <= 5 && !date.equals(LocalDate.of(2026, 1, 6)));

        assertThat(JourneyRunningDays.describe(weekdays)).hasValue("Runs Mon–Fri");
    }

    @Test
    void twoConsecutiveWeekdaysAreNotARange() {
        ServiceDays weekend = days(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 25), date ->
                date.getDayOfWeek().getValue() >= 6);

        assertThat(JourneyRunningDays.describe(weekend)).hasValue("Runs Sat, Sun");
    }

    @Test
    void everyWeekdayReadsDaily() {
        ServiceDays daily = days(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 31), date -> true);

        assertThat(JourneyRunningDays.describe(daily)).hasValue("Runs daily");
    }

    @Test
    void aFewDaysAreIrregular() {
        ServiceDays twoDays = days(LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 9),
                date -> date.getDayOfMonth() % 3 == 0);

        assertThat(JourneyRunningDays.describe(twoDays)).hasValue("Irregular: 2 days in the timetable");
    }

    @Test
    void scatteredDaysWithoutAWeekdayPatternAreCounted() {
        // Mon 5, Tue 13, Wed 21, Thu 29 Jan: each weekday runs once in four weeks
        ServiceDays scattered = days(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 29),
                date -> date.getDayOfMonth() % 8 == 5);

        assertThat(JourneyRunningDays.describe(scattered)).hasValue("Irregular: 4 days in the timetable");
    }

    @Test
    void aSingleDayIsSingular() {
        ServiceDays oneDay = days(LocalDate.of(2025, 12, 12), LocalDate.of(2025, 12, 12), date -> true);

        assertThat(JourneyRunningDays.describe(oneDay)).hasValue("Irregular: 1 day in the timetable");
    }

    @Test
    void noCalendarDataOrNoDaysGiveNoDescription() {
        assertThat(JourneyRunningDays.describe(ServiceDays.EVERY_DAY)).isEmpty();
        assertThat(JourneyRunningDays.describe(ServiceDays.of(new BitSet(), 0))).isEmpty();
    }

    // A ride to the second visit of a station: the ridden trip's first visit there arrives at another time, so it is
    // not one of its own twins (B37)
    @Test
    void aRideToALoopTripsSecondVisitRunsOnTheDaysOfEveryTripMergedIntoIt() {
        TransitGraph graph = TransitGraph.withToday(DAY_ONE);
        graph.addServices(Map.of("x:S1", days(DAY_ONE, DAY_ONE, date -> true),
                "x:S2", days(DAY_ONE.plusDays(1), DAY_ONE.plusDays(1), date -> true)));
        List<Connection> ride = addLoopTrip(graph, "x:T1", "x:S1");
        addLoopTrip(graph, "x:T2", "x:S2");

        ServiceDays days = JourneyRunningDays.of(ride, graph);

        assertThat(days.epochDays()).containsExactly((int) DAY_ONE.toEpochDay(), (int) DAY_ONE.toEpochDay() + 1);
    }

    /** A → B → C → B; returns its connections. */
    private static List<Connection> addLoopTrip(TransitGraph graph, String tripId, String serviceId) {
        List<String> stops = List.of("x:A", "x:B", "x:C", "x:B");
        graph.addTrip(tripId, stops, NO_INFO, serviceId);
        List<Connection> connections = List.of(connection(tripId, stops, 0), connection(tripId, stops, 1),
                connection(tripId, stops, 2));
        connections.forEach(graph::addConnection);
        return connections;
    }

    private static Connection connection(String tripId, List<String> stops, int leg) {
        return Connection.builder().tripId(tripId).routeName("R").fromStopId(stops.get(leg))
                .toStopId(stops.get(leg + 1)).departureTimeSeconds(100 * (2 * leg + 1))
                .arrivalTimeSeconds(100 * (2 * leg + 2)).build();
    }

    private static ServiceDays days(LocalDate first, LocalDate last, Predicate<LocalDate> runs) {
        BitSet bits = new BitSet();
        first.datesUntil(last.plusDays(1)).filter(runs).forEach(date -> bits.set((int) date.toEpochDay()));
        return ServiceDays.of(bits, 0);
    }
}
