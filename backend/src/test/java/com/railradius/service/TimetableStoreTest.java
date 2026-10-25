package com.railradius.service;

import com.railradius.model.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TimetableStoreTest {

    private static final TripInfo NO_INFO = new TripInfo(null, null, null);
    private static final int HOUR_SECONDS = 3600;

    @Test
    void tripsWithTheSameStationsAndTimesBecomeOneThatRunsOnAllTheirDays() {
        RailNetwork network = new RailNetwork();
        network.addServices(Map.of("x:S1", days(100), "x:S2", days(101), "x:S3", days(102)));
        addTrip(network, "x:T2", "x:S2", 0);
        addTrip(network, "x:T1", "x:S1", 0);
        addTrip(network, "x:T3", "x:S3", HOUR_SECONDS);

        TimetableStore store = TimetableStore.build(network);

        assertThat(store.tripCount()).isEqualTo(2);
        assertThat(store.memberTripIds(0)).containsExactly("x:T1", "x:T2");
        assertThat(store.tripId(0)).isEqualTo("x:T1");
        assertThat(store.dayPatterns().days(store.dayPattern(0)).epochDays()).containsExactly(100, 101);
        assertThat(store.memberTripIds(1)).containsExactly("x:T3");
        assertThat(IntStream.range(0, store.stations().size()).mapToObj(store.stations()::id))
                .containsExactly("x:A", "x:B", "x:C");
    }

    @Test
    void departuresAtAStationAreSortedByTimeThenEvent(@TempDir Path dir) throws IOException {
        TimetableStore store = TimetableStore.build(GtfsTestFixtures.network(dir, "zeta"));
        int station = store.stations().indexOf("zeta:Z2").orElseThrow();

        List<Long> departures = IntStream.range(store.departuresStart(station), store.departuresEnd(station))
                .mapToObj(position -> (long) store.departureSecondsAt(position) << Integer.SIZE
                        | store.departureEventAt(position))
                .toList();

        assertThat(departures).hasSizeGreaterThan(1).isSorted();
    }

    @Test
    void eachStationCountsTheStationsItsTripsRunToNext() {
        RailNetwork network = new RailNetwork();
        addTrip(network, "x:T1", null, 0);
        addTrip(network, "x:T2", null, HOUR_SECONDS);
        network.addTrip("x:T3", List.of("x:A", "x:C"), NO_INFO, null);
        network.addConnection(connection("x:T3", "x:A", "x:C", 100, 400));

        TimetableStore store = TimetableStore.build(network);

        assertThat(IntStream.range(0, store.stations().size()).map(store::nextStationCount)).containsExactly(2, 1, 0);
    }

    @Test
    void eachStationCountsTheTripsThatStopThereOnceEvenIfTheyStopTwice() {
        RailNetwork network = new RailNetwork();
        addTrip(network, "x:T1", null, 0);
        network.addTrip("x:T2", List.of("x:A", "x:B", "x:A"), NO_INFO, null);
        network.addConnection(connection("x:T2", "x:A", "x:B", 1000, 1100));
        network.addConnection(connection("x:T2", "x:B", "x:A", 1200, 1300));

        TimetableStore store = TimetableStore.build(network);

        assertThat(IntStream.range(0, store.stations().size()).map(store::stoppingTripCount))
                .containsExactly(2, 2, 1);
    }

    @Test
    void aStationCountsTheTripsOfEveryStopMergedIntoIt(@TempDir Path dir) throws IOException {
        TransitGraph graph = GtfsTestFixtures.load(dir, "alpha", "beta");

        assertThat(graph.stoppingTripCount("beta:B1")).isEqualTo(5);
        assertThat(graph.stoppingTripCount("alpha:A1")).isEqualTo(1);
        assertThat(graph.stoppingTripCount("x:unknown")).isZero();
    }

    @Test
    void stationTableFindsEachStationByItsIdAndNoOther() {
        StationTable table = StationTable.of(List.of("x:A", "x:Bé", "y:A"));

        assertThat(IntStream.range(0, table.size()).mapToObj(table::id)).containsExactly("x:A", "x:Bé", "y:A");
        assertThat(table.indexOf("x:Bé")).hasValue(1);
        assertThat(table.indexOf("y:A")).hasValue(2);
        assertThat(table.indexOf("x:B")).isEmpty();
    }

    @Test
    void aTripWithoutCalendarKeepsEveryDayApartFromAServiceThatNeverRuns() {
        DayPatterns.Builder builder = new DayPatterns.Builder();
        int everyDay = builder.intern(ServiceDays.EVERY_DAY);
        int never = builder.intern(days());
        int someDay = builder.intern(days(100));

        DayPatterns patterns = builder.build();

        assertThat(builder.intern(days(100))).isEqualTo(someDay);
        assertThat(never).isNotEqualTo(everyDay);
        assertThat(patterns.days(everyDay)).isSameAs(ServiceDays.EVERY_DAY);
        assertThat(patterns.weekPeriodMasks(100)).containsExactly(WeekPeriodDays.ALL, 0L,
                WeekPeriodDays.of(days(100), 100));
    }

    @Test
    void rideDaysCountTheTwinTripsThatArriveAtTheSameTime(@TempDir Path dir) throws IOException {
        TimetableStore store = TimetableStore.build(GtfsTestFixtures.network(dir, "zeta"));
        int trip = IntStream.range(0, store.tripCount()).filter(t -> store.tripId(t).equals("zeta:ZT10"))
                .findFirst().orElseThrow();
        int alight = IntStream.range(store.firstEvent(trip), store.endEvent(trip))
                .filter(event -> store.stations().id(store.station(event)).equals("zeta:Z9")).findFirst().orElseThrow();

        ServiceDays days = store.rideDays(store.firstEvent(trip), alight);

        assertThat(JourneyRunningDays.describe(days)).hasValue("Runs daily");
    }

    private static void addTrip(RailNetwork network, String tripId, String serviceId, int laterSeconds) {
        network.addTrip(tripId, List.of("x:A", "x:B", "x:C"), NO_INFO, serviceId);
        network.addConnection(connection(tripId, "x:A", "x:B", 100 + laterSeconds, 200 + laterSeconds));
        network.addConnection(connection(tripId, "x:B", "x:C", 300 + laterSeconds, 400 + laterSeconds));
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
