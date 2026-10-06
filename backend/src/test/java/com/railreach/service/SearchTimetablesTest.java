package com.railreach.service;

import com.railreach.model.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTimetablesTest {

    private static final TripInfo NO_INFO = new TripInfo(null, null, null);

    @Test
    void departuresAtAStopAreSortedByTime(@TempDir Path tempDir) throws IOException {
        SearchTimetables timetables = GtfsTestFixtures.load(tempDir, "zeta").searchTimetables();

        List<TripTimetable.Departure> departures = timetables.departuresAt("zeta:Z2");

        assertThat(departures).extracting(d -> d.trip().tripId())
                .contains("zeta:ZT2", "zeta:ZT3", "zeta:ZT5", "zeta:ZT6");
        assertThat(departures).isSortedAccordingTo(Comparator.comparingInt(TripTimetable.Departure::departureSeconds));
    }

    @Test
    void tripsGetTheWeekPeriodDaysOfTheirServiceAsOfTheGivenDay() {
        TransitGraph graph = new TransitGraph();
        graph.addServices(Map.of("x:S1", days(100)));
        addTrip(graph, "x:T1", "x:S1");

        SearchTimetables timetables = graph.searchTimetables();

        assertThat(timetables.weekPeriodDays(100)).containsExactly(WeekPeriodDays.of(days(100), 100));
        assertThat(timetables.weekPeriodDays(101)).containsExactly(0L);
    }

    @Test
    void eachLegHoldsItsConnectionAndStationsAreNumbered() {
        TransitGraph graph = new TransitGraph();
        addTrip(graph, "x:T1", null);

        SearchTimetables timetables = graph.searchTimetables();
        TripTimetable trip = trip(timetables, "x:A", "x:T1");

        assertThat(trip.legConnections()).extracting(Connection::getToStopId).containsExactly("x:B", "x:C");
        assertThat(trip.stationIndexes()).containsExactly(timetables.stationIndex("x:A"),
                timetables.stationIndex("x:B"), timetables.stationIndex("x:C"));
        assertThat(timetables.stationIndex("x:Unknown")).isEqualTo(-1);
    }

    @Test
    void clearingTheGraphDropsTheTimetables() {
        TransitGraph graph = new TransitGraph();
        addTrip(graph, "x:T1", null);
        assertThat(graph.searchTimetables().tripCount()).isEqualTo(1);

        graph.clear();

        assertThat(graph.searchTimetables().tripCount()).isZero();
    }

    private static TripTimetable trip(SearchTimetables timetables, String stopId, String tripId) {
        return timetables.departuresAt(stopId).stream().map(TripTimetable.Departure::trip)
                .filter(trip -> trip.tripId().equals(tripId)).findFirst().orElseThrow();
    }

    private static void addTrip(TransitGraph graph, String tripId, String serviceId) {
        graph.addTrip(tripId, List.of("x:A", "x:B", "x:C"), NO_INFO, serviceId);
        graph.addConnection(connection(tripId, "x:A", "x:B", 100, 200));
        graph.addConnection(connection(tripId, "x:B", "x:C", 300, 400));
    }

    private static Connection connection(String tripId, String fromId, String toId, int departureSeconds,
                                         int arrivalSeconds) {
        return Connection.builder().tripId(tripId).routeName("R").fromStopId(fromId).toStopId(toId)
                .departureTimeSeconds(departureSeconds).arrivalTimeSeconds(arrivalSeconds).build();
    }

    private static ServiceDays days(int... epochDays) {
        BitSet bits = new BitSet();
        for (int day : epochDays) bits.set(day);
        return ServiceDays.of(bits, 0);
    }
}
