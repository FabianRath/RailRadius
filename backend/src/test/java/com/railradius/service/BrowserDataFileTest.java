package com.railradius.service;

import com.railradius.model.Station;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class BrowserDataFileTest {

    // Coordinates travel as floats
    private static final double COORDINATE_TOLERANCE_DEG = 1e-5;

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
