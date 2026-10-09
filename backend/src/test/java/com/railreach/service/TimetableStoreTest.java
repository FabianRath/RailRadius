package com.railreach.service;

import com.railreach.model.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The S17 gate: the store holds everything SearchTimetables does. Every TripTimetable is rebuilt from the store and
 * compared field by field, and so are the departures, stations, regular departures, week masks and ride days.
 * For dev or full data: {@code mvn -f backend/pom.xml test -Dtest=TimetableStoreTest
 * -Dstore.cache=data/gtfs-dev-cache.json} (full data: add {@code -DargLine=-Xmx12g}).
 */
class TimetableStoreTest {

    private static final TripInfo NO_INFO = new TripInfo(null, null, null);
    private static final int HOUR_SECONDS = 3600;

    @TempDir
    Path tempDir;

    static List<List<String>> feedCombinations() {
        return SearchSnapshotTest.feedCombinations();
    }

    @ParameterizedTest
    @MethodSource("feedCombinations")
    void storeHoldsEverythingTheSearchTimetablesHold(List<String> feeds) throws IOException {
        TransitGraph graph = GtfsTestFixtures.load(tempDir, feeds.toArray(String[]::new));

        assertThat(mismatches(graph)).isEmpty();
    }

    @Test
    @EnabledIfSystemProperty(named = "store.cache", matches = ".+")
    void storeHoldsEverythingTheSearchTimetablesHoldOnACacheFile() {
        String cacheFile = System.getProperty("store.cache");
        TransitGraph graph = new TransitGraph();
        graph.restore(new GtfsCacheStore(cacheFile).load()
                .orElseThrow(() -> new IllegalStateException("No usable cache file " + cacheFile)));

        assertThat(mismatches(graph)).isEmpty();
    }

    @Test
    void mergedTripsKeepTheirMembersInTripIdOrderAndRunOnAllTheirDays() {
        TransitGraph graph = new TransitGraph();
        graph.addServices(Map.of("x:S1", days(100), "x:S2", days(101), "x:S3", days(102)));
        addTrip(graph, "x:T2", "x:S2", 0);
        addTrip(graph, "x:T1", "x:S1", 0);
        addTrip(graph, "x:T3", "x:S3", HOUR_SECONDS);

        TimetableStore store = TimetableStore.build(graph);

        assertThat(store.tripCount()).isEqualTo(2);
        assertThat(store.memberTripIds(0)).containsExactly("x:T1", "x:T2");
        assertThat(store.tripId(0)).isEqualTo("x:T1");
        assertThat(store.dayPatterns().days(store.dayPattern(0)).epochDays()).containsExactly(100, 101);
        assertThat(store.memberTripIds(1)).containsExactly("x:T3");
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
        TimetableStore store = TimetableStore.build(GtfsTestFixtures.load(dir, "zeta"));
        int trip = IntStream.range(0, store.tripCount()).filter(t -> store.tripId(t).equals("zeta:ZT10"))
                .findFirst().orElseThrow();
        int alight = IntStream.range(store.firstEvent(trip), store.endEvent(trip))
                .filter(event -> store.stations().id(store.station(event)).equals("zeta:Z9")).findFirst().orElseThrow();

        ServiceDays days = store.rideDays(store.firstEvent(trip), alight);

        assertThat(JourneyRunningDays.describe(days)).hasValue("Runs daily");
    }

    /** Every difference between the graph's SearchTimetables and its TimetableStore, as text. */
    private static List<String> mismatches(TransitGraph graph) {
        SearchTimetables timetables = graph.searchTimetables();
        TimetableStore store = TimetableStore.build(graph);
        TripTimetable[] trips = (TripTimetable[]) ReflectionTestUtils.getField(timetables, "trips");
        List<String> mismatches = new ArrayList<>();
        if (store.tripCount() != trips.length) mismatches.add("trip count " + store.tripCount());
        if (store.stations().size() != timetables.stationCount()) {
            mismatches.add("station count " + store.stations().size());
        }
        long[] legacyMasks = timetables.weekPeriodDays(graph.todayEpochDay());
        long[] masks = store.dayPatterns().weekPeriodMasks(graph.todayEpochDay());
        for (int trip = 0; trip < Math.min(trips.length, store.tripCount()); trip++) {
            tripMismatch(trips[trip], rebuilt(store, trip)).ifPresent(mismatches::add);
            if (masks[store.dayPattern(trip)] != legacyMasks[trip]) mismatches.add("week mask of trip " + trip);
            for (int stopIdx = 0; stopIdx < trips[trip].stopCount(); stopIdx++) {
                if (store.isRegularDeparture(store.firstEvent(trip) + stopIdx)
                        != timetables.regularDepartures(trips[trip])[stopIdx]) {
                    mismatches.add("regular departure " + trips[trip].tripId() + " at " + stopIdx);
                }
            }
            mismatches.addAll(rideDaysMismatches(graph, store, trips[trip]));
        }
        for (int station = 0; station < store.stations().size(); station++) {
            mismatches.addAll(stationMismatches(timetables, store, station));
        }
        List<String> members = IntStream.range(0, store.tripCount())
                .mapToObj(store::memberTripIds).flatMap(List::stream).sorted().toList();
        if (!members.equals(graph.tripIds().stream().sorted().toList())) mismatches.add("member trip IDs");
        return mismatches;
    }

    private static List<String> stationMismatches(SearchTimetables timetables, TimetableStore store, int station) {
        String stationId = store.stations().id(station);
        List<String> mismatches = new ArrayList<>();
        if (timetables.stationIndex(stationId) != station) mismatches.add("station index of " + stationId);
        if (store.stations().indexOf(stationId).orElse(-1) != station) mismatches.add("lookup of " + stationId);
        List<String> legacy = timetables.departuresAt(stationId).stream()
                .map(d -> d.trip().index() + "/" + d.stopIdx() + "@" + d.departureSeconds()).toList();
        List<String> fromStore = IntStream.range(store.departuresStart(station), store.departuresEnd(station))
                .mapToObj(position -> {
                    int event = store.departureEventAt(position);
                    int trip = store.tripOf(event);
                    return trip + "/" + (event - store.firstEvent(trip)) + "@" + store.departureSecondsAt(position);
                }).toList();
        if (!legacy.equals(fromStore)) mismatches.add("departures at " + stationId);
        return mismatches;
    }

    /**
     * The store's ride days against JourneyRunningDays, for each leg and each ride to the end of the trip. A journey
     * of one ride runs on that ride's days from today on.
     */
    private static List<String> rideDaysMismatches(TransitGraph graph, TimetableStore store, TripTimetable trip) {
        List<String> mismatches = new ArrayList<>();
        int first = store.firstEvent(trip.index());
        for (int board = 0; board < trip.stopCount() - 1; board++) {
            if (!trip.hasLeg(board)) continue;
            int last = board + 1;
            while (last < trip.stopCount() - 1 && trip.hasLeg(last)) last++;
            for (int alight : new int[] {board + 1, last}) {
                List<Connection> ride = Arrays.asList(trip.legConnections()).subList(board, alight);
                ServiceDays days = graph.upcomingDays(store.rideDays(first + board, first + alight));
                if (!sameDays(days, JourneyRunningDays.of(ride, graph))) {
                    mismatches.add("ride days " + trip.tripId() + " " + board + "->" + alight);
                }
            }
        }
        return mismatches;
    }

    /** The trip as a TripTimetable, from the store alone. */
    private static TripTimetable rebuilt(TimetableStore store, int trip) {
        int first = store.firstEvent(trip);
        int stopCount = store.endEvent(trip) - first;
        int[] stationIndexes = IntStream.range(first, first + stopCount).map(store::station).toArray();
        List<String> stops = Arrays.stream(stationIndexes).mapToObj(store.stations()::id).toList();
        Connection[] legs = new Connection[Math.max(stopCount - 1, 0)];
        for (int leg = 0; leg < legs.length; leg++) {
            if (!store.hasLeg(first + leg)) continue;
            legs[leg] = Connection.builder().tripId(store.tripId(trip)).routeName(store.routeName(trip).orElse(null))
                    .fromStopId(stops.get(leg)).toStopId(stops.get(leg + 1))
                    .departureTimeSeconds(store.departureSeconds(first + leg))
                    .arrivalTimeSeconds(store.arrivalSeconds(first + leg + 1)).build();
        }
        return TripTimetable.of(trip, store.tripId(trip), stops, stationIndexes,
                store.dayPatterns().days(store.dayPattern(trip)), legs);
    }

    private static Optional<String> tripMismatch(TripTimetable legacy, TripTimetable rebuilt) {
        boolean same = legacy.index() == rebuilt.index() && legacy.tripId().equals(rebuilt.tripId())
                && legacy.stops().equals(rebuilt.stops())
                && Arrays.equals(legacy.stationIndexes(), rebuilt.stationIndexes())
                && sameDays(legacy.serviceDays(), rebuilt.serviceDays())
                && Arrays.equals(legacy.legConnections(), rebuilt.legConnections())
                && Arrays.equals(legacy.legDepartureSeconds(), rebuilt.legDepartureSeconds())
                && Arrays.equals(legacy.legArrivalSeconds(), rebuilt.legArrivalSeconds())
                && Arrays.equals(legacy.departureOffsetMinutes(), rebuilt.departureOffsetMinutes())
                && Arrays.equals(legacy.arrivalOffsetMinutes(), rebuilt.arrivalOffsetMinutes());
        return same ? Optional.empty() : Optional.of("trip " + legacy.tripId());
    }

    private static boolean sameDays(ServiceDays a, ServiceDays b) {
        return (a == ServiceDays.EVERY_DAY) == (b == ServiceDays.EVERY_DAY)
                && Arrays.equals(a.epochDays().toArray(), b.epochDays().toArray());
    }

    private static void addTrip(TransitGraph graph, String tripId, String serviceId, int laterSeconds) {
        graph.addTrip(tripId, List.of("x:A", "x:B", "x:C"), NO_INFO, serviceId);
        graph.addConnection(connection(tripId, "x:A", "x:B", 100 + laterSeconds, 200 + laterSeconds));
        graph.addConnection(connection(tripId, "x:B", "x:C", 300 + laterSeconds, 400 + laterSeconds));
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
