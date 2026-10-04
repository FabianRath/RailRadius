package com.railreach.service;

import com.railreach.dto.JourneyLeg;
import com.railreach.dto.ReachabilityRequest;
import com.railreach.dto.ReachabilityResponse;
import com.railreach.dto.ReachableStation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReachabilityServiceTest {

    @TempDir
    static Path tempDir;

    static TransitGraph gtfs;

    @BeforeAll
    static void loadFixture() throws Exception {
        gtfs = GtfsTestFixtures.load(tempDir, "alpha", "beta");
    }

    @Test
    void zeroTransfersOnlyReachesStationsOnDirectTrips() {
        ReachabilityResponse response = query(gtfs, "alpha:A1", 0, 0, 180);

        assertThat(find(response, "alpha:A2")).isPresent();
        assertThat(find(response, "beta:B1")).get()
                .extracting(ReachableStation::getTransfers).isEqualTo(0);
        assertThat(find(response, "beta:B2")).isEmpty();
    }

    @Test
    void oneTransferReachesStationBehindTheBorder() {
        ReachableStation b2 = find(query(gtfs, "alpha:A1", 1, 0, 180), "beta:B2").orElseThrow();

        assertThat(b2.getTransfers()).isEqualTo(1);
        assertThat(b2.getTotalDurationMinutes()).isEqualTo(120); // 60 ride + 20 wait + 40 ride
        assertThat(b2.getPathNames())
                .containsExactly("Alpha Central", "Alpha North", "Border Town Station", "Beta City");
    }

    @Test
    void legsMergeSegmentsOfTheSameTripAndInsertTransfers() {
        ReachableStation b2 = find(query(gtfs, "alpha:A1", 1, 0, 180), "beta:B2").orElseThrow();

        assertThat(b2.getLegs()).extracting(JourneyLeg::getType)
                .containsExactly("TRAIN", "TRANSFER", "TRAIN");
        JourneyLeg first = b2.getLegs().get(0);
        assertThat(first.getTrainName()).isEqualTo("RE1");
        assertThat(first.getDepartureTime()).isEqualTo("08:00:00");
        assertThat(first.getArrivalTime()).isEqualTo("09:00:00");
        assertThat(b2.getLegs().get(1).getDurationMinutes()).isEqualTo(20);
        assertThat(b2.getLegs().get(2).getTrainName()).isEqualTo("IC5");
    }

    @Test
    void legsShowTheNameOfAStationMergedAcrossFeeds() {
        // T1 ends at alpha:A3, which was merged into beta:B1 "Border Town Station"
        ReachableStation b2 = find(query(gtfs, "alpha:A1", 1, 0, 180), "beta:B2").orElseThrow();

        assertThat(b2.getLegs().get(0).getToStation()).isEqualTo("Border Town Station");
        assertThat(b2.getLegs().get(1).getFromStation()).isEqualTo("Border Town Station");
    }

    @Test
    void stationAndLegsCarryTheCountries(@TempDir Path dir) throws Exception {
        // KT1 runs from K1 in KA to L1 in LA (K3 was snapped into it), then LT1 continues to L2
        TransitGraph graph = GtfsTestFixtures.load(dir, "kappa", "lambda");
        ReachableStation l2 = find(query(graph, "kappa:K1", 1, 0, 180), "lambda:L2").orElseThrow();

        assertThat(l2.getCountry()).isEqualTo("LA");
        assertThat(l2.getLegs()).extracting(JourneyLeg::getFromCountry).containsExactly("KA", "LA", "LA");
        assertThat(l2.getLegs()).extracting(JourneyLeg::getToCountry).containsExactly("LA", "LA", "LA");
    }

    @Test
    void transferShorterThanMinimumIsRejected() {
        // Only feasible transfer at the border is 20 min
        assertThat(find(query(gtfs, "alpha:A1", 1, 30, 180), "beta:B2")).isEmpty();
    }

    @Test
    void transferLongerThanMaximumIsRejected() {
        assertThat(find(query(gtfs, "alpha:A1", 1, 0, 19), "beta:B2")).isEmpty();
        assertThat(find(query(gtfs, "alpha:A1", 1, 0, 20), "beta:B2")).isPresent();
    }

    @Test
    void transferAcrossMidnightWrapsToNextDay() {
        // NJ arrives 23:30, EN departs 00:10 -> 40 min wait, not -1400
        ReachableStation b2 = find(query(gtfs, "alpha:A4", 1, 0, 180), "beta:B2").orElseThrow();

        assertThat(b2.getTotalDurationMinutes()).isEqualTo(140); // 40 ride + 40 wait + 60 ride
        assertThat(b2.getLegs()).extracting(JourneyLeg::getTrainName)
                .containsExactly("NJ", null, "EN");
    }

    // B2: the faster T6 evening train must not hide the T1 -> U1 connection
    @Test
    void slowerEarlierArrivalIsKeptWhenItEnablesAFasterTransfer(@TempDir Path dir) throws Exception {
        TransitGraph withFastTrain = GtfsTestFixtures.load(dir, "alpha-fast-train", "beta");

        ReachableStation b2 = find(query(withFastTrain, "alpha:A1", 1, 0, 180), "beta:B2").orElseThrow();

        assertThat(b2.getTotalDurationMinutes()).isEqualTo(120); // not 250 via T6 -> U3
    }

    // B3: the faster Y2 arrival misses Y3 with a 5 min minimum, the slower Y1 arrival still catches it
    @Test
    void minimumTransferTimeKeepsTheSlowerArrivalThatCatchesTheTrain(@TempDir Path dir) throws Exception {
        TransitGraph epsilon = GtfsTestFixtures.load(dir, "epsilon");

        assertThat(find(query(epsilon, "epsilon:E1", 1, 0, 180), "epsilon:E4")).get()
                .extracting(ReachableStation::getTotalDurationMinutes).isEqualTo(40); // Y2, 2 min wait, Y3
        assertThat(find(query(epsilon, "epsilon:E1", 1, 5, 180), "epsilon:E4")).get()
                .extracting(ReachableStation::getTotalDurationMinutes).isEqualTo(90); // Y1, 20 min wait, Y3
    }

    // B4: without a wait limit, E6 is reached with 1 transfer and an 11.5 h wait, which is over the time limit
    @Test
    void stationOverTheTimeLimitWithFewestTransfersShowsTheFasterPathWithMoreTransfers(@TempDir Path dir)
            throws Exception {
        ReachabilityService service = GtfsTestFixtures.reachabilityService(GtfsTestFixtures.load(dir, "epsilon"));
        ReachabilityRequest.ReachabilityRequestBuilder unlimitedWait = ReachabilityRequest.builder()
                .startStationId("epsilon:E1")
                .maxTransfers(2)
                .minTransferTimeMinutes(0)
                .maxTransferTimeMinutes(99999);

        ReachableStation anyTime = find(service.calculateReachability(unlimitedWait.build()), "epsilon:E6")
                .orElseThrow();
        ReachableStation withinTwoHours = find(service.calculateReachability(
                unlimitedWait.maxJourneyTimeHours(2.0).build()), "epsilon:E6").orElseThrow();

        assertThat(anyTime.getTransfers()).isEqualTo(1);
        assertThat(anyTime.getTotalDurationMinutes()).isEqualTo(750); // X1, wait until 22:00, X4
        assertThat(withinTwoHours.getTransfers()).isEqualTo(2);
        assertThat(withinTwoHours.getTotalDurationMinutes()).isEqualTo(90); // X1, X2, X3
    }

    @Test
    void doneEventCountsOnlyStationsWithinMaxJourneyTime() {
        // A2 (30 min) and A3/B1 (60 min) fit into 1 h, B2 (120 min) does not
        RecordingEmitter emitter = new RecordingEmitter();
        ReachabilityService service = GtfsTestFixtures.reachabilityService(gtfs);
        service.calculateReachabilityStreaming(ReachabilityRequest.builder()
                .startStationId("alpha:A1")
                .maxTransfers(1)
                .minTransferTimeMinutes(0)
                .maxTransferTimeMinutes(180)
                .maxJourneyTimeHours(1.0)
                .build(), emitter);

        long streamedCount = emitter.payloads.stream()
                .filter(List.class::isInstance)
                .mapToLong(batch -> ((List<?>) batch).size())
                .sum();
        Map<?, ?> done = emitter.payloads.stream()
                .filter(Map.class::isInstance).map(Map.class::cast)
                .findFirst().orElseThrow();
        assertThat(streamedCount).isEqualTo(2);
        assertThat(done.get("total")).isEqualTo(2);
    }

    @Test
    void uncachedStreamSendsEachStationOnceAndMatchesTheCachedRepeat() {
        ReachabilityService service = GtfsTestFixtures.reachabilityService(gtfs);
        ReachabilityRequest request = ReachabilityRequest.builder()
                .startStationId("alpha:A1")
                .maxTransfers(1)
                .minTransferTimeMinutes(0)
                .maxTransferTimeMinutes(180)
                .build();

        RecordingEmitter first = new RecordingEmitter();
        service.calculateReachabilityStreaming(request, first);
        RecordingEmitter repeat = new RecordingEmitter();
        service.calculateReachabilityStreaming(request, repeat);

        assertThat(first.streamedStopIds()).doesNotHaveDuplicates().doesNotContain("alpha:A1");
        assertThat(first.streamedStopIds()).containsExactlyInAnyOrderElementsOf(repeat.streamedStopIds());
        assertThat(first.done().get("total")).isEqualTo(first.streamedStopIds().size());
        assertThat(repeat.done().get("total")).isEqualTo(repeat.streamedStopIds().size());
    }

    /** Captures the data objects of every sent SSE event instead of writing them to a response. */
    private static class RecordingEmitter extends SseEmitter {
        final List<Object> payloads = new ArrayList<>();

        @Override
        public void send(SseEventBuilder event) {
            event.build().stream().map(DataWithMediaType::getData)
                    .filter(data -> !(data instanceof String))
                    .forEach(payloads::add);
        }

        List<String> streamedStopIds() {
            return payloads.stream()
                    .filter(List.class::isInstance)
                    .flatMap(batch -> ((List<?>) batch).stream())
                    .map(station -> ((ReachableStation) station).getStopId())
                    .toList();
        }

        Map<?, ?> done() {
            return payloads.stream()
                    .filter(Map.class::isInstance).map(Map.class::cast)
                    .findFirst().orElseThrow();
        }
    }

    private static ReachabilityResponse query(TransitGraph data, String start, int maxTransfers,
                                              int minTransferMins, int maxTransferMins) {
        return GtfsTestFixtures.reachabilityService(data).calculateReachability(ReachabilityRequest.builder()
                .startStationId(start)
                .maxTransfers(maxTransfers)
                .minTransferTimeMinutes(minTransferMins)
                .maxTransferTimeMinutes(maxTransferMins)
                .build());
    }

    private static Optional<ReachableStation> find(ReachabilityResponse response, String stopId) {
        return response.getReachableStations().stream()
                .filter(s -> s.getStopId().equals(stopId))
                .findFirst();
    }
}
