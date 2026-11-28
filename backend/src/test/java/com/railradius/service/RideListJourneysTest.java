package com.railradius.service;

import com.railradius.dto.ReachableStation;
import com.railradius.dto.RideListJourneyRequest;
import com.railradius.dto.RidePosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.railradius.service.GtfsTestFixtures.VISITOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RideListJourneysTest {

    private static final int MIN_WAIT_MINUTES = 0;
    private static final int MAX_WAIT_MINUTES = 180;
    // The cheapest budget SearchRateLimiter allows: the biggest search's cost
    private static final int SMALLEST_BUDGET = 87;

    @TempDir
    Path tempDir;

    private record Fixture(TransitGraph graph, BrowserDataStore browserData, RideListJourneys journeys) {

        String version() {
            return browserData.current().orElseThrow().version();
        }

        CompactResult search(String startId, int maxTransfers) {
            return GtfsTestFixtures.search(graph).search(startId, maxTransfers, MIN_WAIT_MINUTES, MAX_WAIT_MINUTES,
                    CompactReachabilitySearch.NO_DEADLINE, paths -> {});
        }
    }

    private Fixture fixture(SearchRateLimiter rateLimiter, String... feeds) throws IOException {
        TransitGraph graph = GtfsTestFixtures.load(tempDir.resolve("load"), feeds);
        BrowserDataStore browserData = GtfsTestFixtures.browserDataStore(tempDir, graph);
        browserData.buildServed();
        return new Fixture(graph, browserData,
                new RideListJourneys(browserData, rateLimiter, new JourneyLegBuilder(),
                        GtfsTestFixtures.NO_TRACK_LINES, graph));
    }

    private Fixture fixture(String... feeds) throws IOException {
        return fixture(GtfsTestFixtures.unlimitedRateLimiter(), feeds);
    }

    private static RideListJourneyRequest request(String version, List<CompactResult.Ride> rides) {
        return new RideListJourneyRequest(version, MIN_WAIT_MINUTES, MAX_WAIT_MINUTES, rides.stream()
                .map(ride -> new RidePosition(ride.trip(), ride.boardEvent(), ride.alightEvent())).toList());
    }

    private static List<CompactResult.Ride> ridesTo(CompactResult result, String stationId, int transfers) {
        return result.rideList(stationId, transfers).rides();
    }

    @Test
    void everyJourneyBuiltFromItsRidesEqualsTheOneBuiltFromTheSearchResult() throws IOException {
        for (String[] feeds : List.of(new String[] {"alpha", "beta"}, new String[] {"zeta"})) {
            Fixture fixture = fixture(feeds);
            JourneyLegBuilder legBuilder = new JourneyLegBuilder();
            List<String> checked = new ArrayList<>();
            for (String startId : fixture.graph().getAllStations().keySet()) {
                CompactResult result = fixture.search(startId, SearchOptions.MAX_TRANSFERS);
                result.forEachStation((stationId, paths) -> {
                    if (stationId.equals(startId)) return;
                    for (CompactResult.PathSummary path : paths) {
                        ReachableStation fromRides = fixture.journeys().journey(VISITOR,
                                request(fixture.version(), ridesTo(result, stationId, path.transfers()))).orElseThrow();
                        assertThat(fromRides).isEqualTo(
                                legBuilder.toReachableStation(result, stationId, path.transfers()).orElseThrow());
                        checked.add(startId + " " + stationId + " " + path.transfers());
                    }
                });
            }
            assertThat(checked).anyMatch(journey -> journey.endsWith(" 1"));
        }
    }

    @Test
    void ridesOfAnotherDataVersionAreRejected() throws IOException {
        Fixture fixture = fixture("alpha", "beta");
        List<CompactResult.Ride> rides = ridesTo(fixture.search("alpha:A1", 1), "beta:B2", 1);

        assertThatThrownBy(() -> fixture.journeys().journey(VISITOR, request("0123456789abcdef", rides)))
                .isInstanceOf(SearchRefusal.class).hasMessageContaining("timetable has changed");
    }

    @Test
    void aRideWhoseStopsAreNotOnItsTripIsRejected() throws IOException {
        Fixture fixture = fixture("alpha", "beta");
        List<CompactResult.Ride> rides = ridesTo(fixture.search("alpha:A1", 1), "beta:B2", 1);
        CompactResult.Ride second = rides.get(1);
        CompactResult.Ride onFirstTrip = new CompactResult.Ride(rides.getFirst().trip(), second.boardEvent(),
                second.alightEvent());

        assertThatThrownBy(() -> fixture.journeys().journey(VISITOR,
                request(fixture.version(), List.of(rides.getFirst(), onFirstTrip))))
                .isInstanceOf(SearchRefusal.class).hasMessageContaining("not on its trip");
    }

    @Test
    void aRideThatAlightsBeforeItBoardsIsRejected() throws IOException {
        Fixture fixture = fixture("alpha", "beta");
        CompactResult.Ride ride = ridesTo(fixture.search("alpha:A1", 0), "alpha:A2", 0).getFirst();
        CompactResult.Ride backwards = new CompactResult.Ride(ride.trip(), ride.alightEvent(), ride.boardEvent());

        assertThatThrownBy(() -> fixture.journeys().journey(VISITOR, request(fixture.version(), List.of(backwards))))
                .isInstanceOf(SearchRefusal.class).hasMessageContaining("alight after it boards");
    }

    @Test
    void ridesThatDoNotMeetAtAStationAreRejected() throws IOException {
        Fixture fixture = fixture("alpha", "beta");
        List<CompactResult.Ride> rides = ridesTo(fixture.search("alpha:A1", 1), "beta:B2", 1);

        assertThatThrownBy(() -> fixture.journeys().journey(VISITOR,
                request(fixture.version(), List.of(rides.get(1), rides.getFirst()))))
                .isInstanceOf(SearchRefusal.class).hasMessageContaining("board where the one before it alights");
    }

    @Test
    void aChangeShorterThanTheMinimumWaitIsRejected() throws IOException {
        Fixture fixture = fixture("alpha", "beta");
        // The change at Border Town takes 20 min
        RideListJourneyRequest request = request(fixture.version(),
                ridesTo(fixture.search("alpha:A1", 1), "beta:B2", 1));
        request.setMinTransferMinutes(30);

        assertThatThrownBy(() -> fixture.journeys().journey(VISITOR, request))
                .isInstanceOf(SearchRefusal.class).hasMessageContaining("outside the allowed wait");
    }

    @Test
    void moreRidesThanTheMostTransfersAllowAreRejected() throws IOException {
        Fixture fixture = fixture("alpha", "beta");
        CompactResult.Ride ride = ridesTo(fixture.search("alpha:A1", 0), "alpha:A2", 0).getFirst();

        assertThatThrownBy(() -> fixture.journeys().journey(VISITOR,
                request(fixture.version(), Collections.nCopies(SearchOptions.MAX_TRANSFERS + 2, ride))))
                .isInstanceOf(SearchRefusal.class).hasMessageContaining("1 to 6 rides");
        assertThatThrownBy(() -> fixture.journeys().journey(VISITOR, request(fixture.version(), List.of())))
                .isInstanceOf(SearchRefusal.class).hasMessageContaining("1 to 6 rides");
    }

    @Test
    void eachJourneyIsChargedToTheClientButInvalidRidesAreNot() throws IOException {
        Fixture fixture = fixture(new SearchRateLimiter(SMALLEST_BUDGET, 1, () -> 0L), "alpha", "beta");
        List<CompactResult.Ride> rides = ridesTo(fixture.search("alpha:A1", 1), "beta:B2", 1);
        RideListJourneyRequest valid = request(fixture.version(), rides);
        RideListJourneyRequest invalid = request(fixture.version(), rides.reversed());

        for (int i = 0; i < SMALLEST_BUDGET; i++) {
            assertThat(fixture.journeys().journey(VISITOR, valid)).isPresent();
            assertThatThrownBy(() -> fixture.journeys().journey(VISITOR, invalid))
                    .isInstanceOf(SearchRefusal.class).hasMessageContaining("board where");
        }

        assertThatThrownBy(() -> fixture.journeys().journey(VISITOR, valid))
                .isInstanceOf(SearchRefusal.class).hasMessageContaining("Too many searches");
        assertThat(fixture.journeys().journey(new SearchAdmission.Client(VISITOR.ip(), true), valid)).isPresent();
    }
}
