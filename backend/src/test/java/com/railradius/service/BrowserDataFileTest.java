package com.railradius.service;

import com.railradius.model.Station;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class BrowserDataFileTest {

    // Coordinates travel as floats
    private static final double COORDINATE_TOLERANCE_DEG = 1e-5;
    // Relative to backend/, where Maven runs the tests; the browser engine's build check reads it (browser-engine/)
    private static final Path ENGINE_CHECK_DIR = Path.of("target/browser-engine-check");
    private static final String SAME_RESULT_SUFFIX = ".same-result.txt";

    @TempDir
    Path tempDir;

    static List<List<String>> feedCombinations() {
        return SearchSnapshotTest.feedCombinations();
    }

    @ParameterizedTest
    @MethodSource("feedCombinations")
    void aReadBackStoreHasEverythingTheSearchReads(List<String> feeds) throws IOException {
        ServedGraph served = GtfsTestFixtures.load(tempDir, feeds.toArray(String[]::new)).served();

        BrowserDataFile.BrowserData read = writeAndRead(served);

        assertThat(searchView(read.store())).containsExactlyElementsOf(searchView(served.store()));
    }

    /**
     * Strict: the same rides at the same store positions as the server. Same result: what the recording holds (#32).
     * The file, the server's strict results and the same-result recording are also left in {@link #ENGINE_CHECK_DIR}:
     * the browser engine's build check searches the same file in WebAssembly and must find the same (#10).
     */
    @ParameterizedTest
    @MethodSource("feedCombinations")
    void searchingTheFileFindsWhatTheServerFinds(List<String> feeds) throws IOException {
        TransitGraph server = GtfsTestFixtures.load(tempDir, feeds.toArray(String[]::new));
        Path file = tempDir.resolve("browser-data.bin");
        BrowserDataFile.write(server.served(), file);
        TransitGraph browser = TransitGraph.withToday(GtfsTestFixtures.TODAY);
        browser.serve(BrowserDataFile.read(file));

        String serverDump = SearchSnapshotTest.dump(server, SearchSnapshot.RideFormat.STORE_POSITIONS);

        assertThat(SearchSnapshotTest.dump(browser, SearchSnapshot.RideFormat.STORE_POSITIONS)).isEqualTo(serverDump);
        Path sameResult = SearchSnapshotTest.recording(feeds, SearchSnapshot.RideFormat.STATIONS_AND_TIMES);
        assertThat(SearchSnapshotTest.dump(browser, SearchSnapshot.RideFormat.STATIONS_AND_TIMES))
                .isEqualTo(Files.readString(sameResult));
        String combination = String.join("+", feeds);
        Files.createDirectories(ENGINE_CHECK_DIR);
        Files.copy(file, ENGINE_CHECK_DIR.resolve(combination + ".bin"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(ENGINE_CHECK_DIR.resolve(combination + ".txt"), serverDump);
        Files.copy(sameResult, ENGINE_CHECK_DIR.resolve(combination + SAME_RESULT_SUFFIX),
                StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void stationsKeepTheirNamesCountriesAndCoordinates() throws IOException {
        ServedGraph served = GtfsTestFixtures.load(tempDir, "kappa", "lambda").served();

        List<Station> stations = writeAndRead(served).stations();

        assertThat(stations).hasSize(served.store().stations().size());
        for (Station read : stations) {
            Station original = served.station(read.getId());
            assertThat(read.getName()).isEqualTo(original.getName());
            assertThat(read.getCountry()).isEqualTo(original.getCountry());
            assertThat(read.getLatitude()).isCloseTo(original.getLatitude(), offset(COORDINATE_TOLERANCE_DEG));
            assertThat(read.getLongitude()).isCloseTo(original.getLongitude(), offset(COORDINATE_TOLERANCE_DEG));
        }
        assertThat(stations).extracting(Station::getCountry).contains("KA", "LA");
    }

    // The trip runs every day, so its days alone would make its departure regular: the file must keep the server's
    // flag instead, which counts days the file may leave out (#47)
    @Test
    void regularDeparturesAreTheServersNotWorkedOutFromTheFilesDays() throws IOException {
        List<String> ids = List.of("x:A", "x:B");
        DayPatterns.Builder dayPatterns = new DayPatterns.Builder();
        int everyDay = dayPatterns.intern(ServiceDays.EVERY_DAY);
        TimetableStoreBuilder.Parts parts = TimetableStoreBuilder.withoutTripNames(StationTable.of(ids),
                new int[] {0, 2}, new int[] {0, 1}, new int[] {3600, TimetableStore.NO_LEG},
                new int[] {TimetableStore.NO_LEG, 4200}, dayPatterns.build(), new int[] {everyDay});
        assertThat(TimetableStore.of(parts).isRegularDeparture(0)).isTrue();
        TimetableStore noneRegular = TimetableStore.of(parts, new long[1]);
        ServedGraph served = new ServedGraph(Map.of("x:A", Station.builder().id("x:A").build(),
                "x:B", Station.builder().id("x:B").build()), Map.of(), TripInfos.EMPTY, noneRegular, List.of(),
                StationSearchIndex.Index.EMPTY);

        assertThat(writeAndRead(served).store().isRegularDeparture(0)).isFalse();
    }

    // Trip 0 runs past midnight (seconds over 24 h) and leaves B 10 s before it arrives there: a negative difference.
    // Trip 1 runs the same profile an hour later, and trip 2 has no time at all; every second must come back (#49)
    @Test
    void timesPastMidnightAndNegativeDifferencesReadBackExactly() throws IOException {
        int noLeg = TimetableStore.NO_LEG;
        List<String> ids = List.of("x:A", "x:B", "x:C");
        DayPatterns.Builder dayPatterns = new DayPatterns.Builder();
        int everyDay = dayPatterns.intern(ServiceDays.EVERY_DAY);
        TimetableStoreBuilder.Parts parts = TimetableStoreBuilder.withoutTripNames(StationTable.of(ids),
                new int[] {0, 3, 6, 8}, new int[] {0, 1, 2, 0, 1, 2, 0, 1},
                new int[] {86_000, 89_990, noLeg, 89_600, 93_590, noLeg, noLeg, noLeg},
                new int[] {noLeg, 90_000, 100_000, noLeg, 93_600, 103_600, noLeg, noLeg},
                dayPatterns.build(), new int[] {everyDay, everyDay, everyDay});
        TimetableStore store = TimetableStore.of(parts);
        ServedGraph served = new ServedGraph(Map.of("x:A", Station.builder().id("x:A").build(),
                "x:B", Station.builder().id("x:B").build(), "x:C", Station.builder().id("x:C").build()), Map.of(),
                TripInfos.EMPTY, store, List.of(), StationSearchIndex.Index.EMPTY);

        assertThat(searchView(writeAndRead(served).store())).containsExactlyElementsOf(searchView(store));
    }

    @Test
    void theNoticeNamesTheLicenceAndEveryFeedWithItsDays() {
        List<FeedTimespan> feeds = List.of(feed("alpha", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 12)),
                feed("beta", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 30)));

        String notice = BrowserDataFile.notice(feeds);

        assertThat(notice).contains("Open Database License", "alpha 2026-01-01 2026-12-12",
                "beta 2026-03-01 2026-06-30");
    }

    private static FeedTimespan feed(String source, LocalDate firstDay, LocalDate lastDay) {
        return new FeedTimespan(source, new FeedValidity(firstDay, lastDay), List.of());
    }

    private BrowserDataFile.BrowserData writeAndRead(ServedGraph served) throws IOException {
        Path file = tempDir.resolve("browser-data.bin");
        BrowserDataFile.write(served, file);
        return BrowserDataFile.read(file);
    }

    /** One line per event, trip and station with every value the search reads from the store. */
    private static List<String> searchView(TimetableStore store) {
        List<String> lines = new ArrayList<>();
        for (int event = 0; event < store.eventCount(); event++) {
            lines.add("event " + event + " trip " + store.tripOf(event) + " station " + store.station(event)
                    + " arr " + store.arrivalSeconds(event) + " dep " + store.departureSeconds(event)
                    + " regular " + store.isRegularDeparture(event));
        }
        for (int trip = 0; trip < store.tripCount(); trip++) {
            ServiceDays days = store.dayPatterns().days(store.dayPattern(trip));
            lines.add("trip " + trip + " events " + store.firstEvent(trip) + "-" + store.endEvent(trip)
                    + " days " + (days == ServiceDays.EVERY_DAY ? "every day" : days.epochDays().boxed().toList()));
        }
        for (int station = 0; station < store.stations().size(); station++) {
            List<String> departures = new ArrayList<>();
            for (int position = store.departuresStart(station); position < store.departuresEnd(station); position++) {
                departures.add(store.departureSecondsAt(position) + "@" + store.departureEventAt(position));
            }
            lines.add("station " + station + " " + store.stations().id(station) + " next "
                    + store.nextStationCount(station) + " trips " + store.stoppingTripCount(station)
                    + " departures " + departures);
        }
        return lines;
    }
}
