package com.railreach.service;

import com.railreach.dto.JourneyLeg;
import com.railreach.dto.ReachabilityRequest;
import com.railreach.dto.ReachabilityResponse;
import com.railreach.dto.ReachableStation;
import com.railreach.dto.ReachableStationSummary;
import com.railreach.service.ReachabilityCache.SearchKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ReachabilityServiceTest {

    private static final int A_DAY_MINUTES = 24 * 60;
    private static final int LONGEST_MIN_WAIT_MINUTES = 1300;

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
    void trainLegsCarryTrainNumberHeadsignAndOperator() {
        ReachableStation b2 = find(query(gtfs, "alpha:A1", 1, 0, 180), "beta:B2").orElseThrow();

        // alpha's routes name no agency, so its only agency is the operator
        JourneyLeg t1 = b2.getLegs().get(0);
        assertThat(t1.getTrainNumber()).isEqualTo("RE 4711");
        assertThat(t1.getHeadsign()).isEqualTo("Border Town");
        assertThat(t1.getOperator()).isEqualTo("Alpha Rail");
        // U1's trip_short_name is "000123"
        JourneyLeg u1 = b2.getLegs().get(2);
        assertThat(u1.getTrainNumber()).isEqualTo("123");
        assertThat(u1.getOperator()).isEqualTo("Beta Rail");
        assertThat(b2.getLegs().get(1).getTrainNumber()).isNull();
    }

    @Test
    void legsShowTheNameOfAStationMergedAcrossFeeds() {
        // T1 ends at alpha:A3, which was merged into beta:B1 "Border Town Station"
        ReachableStation b2 = find(query(gtfs, "alpha:A1", 1, 0, 180), "beta:B2").orElseThrow();

        assertThat(b2.getLegs().get(0).getToStation()).isEqualTo("Border Town Station");
        assertThat(b2.getLegs().get(1).getFromStation()).isEqualTo("Border Town Station");
    }

    @Test
    void legsCarryTheStopIdsOfTheirPathStations() {
        // T1 runs A1 → A2 → A3 (merged into beta:B1), U1 continues to B2
        ReachableStation b2 = find(query(gtfs, "alpha:A1", 1, 0, 180), "beta:B2").orElseThrow();

        assertThat(b2.getPath()).containsExactly("alpha:A1", "alpha:A2", "beta:B1", "beta:B2");
        assertThat(b2.getLegs()).extracting(JourneyLeg::getFromStopId)
                .containsExactly("alpha:A1", "beta:B1", "beta:B1");
        assertThat(b2.getLegs()).extracting(JourneyLeg::getToStopId)
                .containsExactly("beta:B1", "beta:B1", "beta:B2");
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

    // V1 reaches D1 at 08:30, W1 leaves it at 08:00: under an hour earlier is no 23.5 h wait past midnight. The
    // minimum wait rules out V2 (10:30) → W1 (21.5 h) but not V2 → W2 at 09:00 (22.5 h).
    @Test
    void trainLeavingLessThanAnHourBeforeTheArrivalIsNotCaughtTheNextDay(@TempDir Path dir) throws Exception {
        ReachabilityResponse response = query(GtfsTestFixtures.load(dir, "gamma", "delta"), "gamma:G3", 1,
                LONGEST_MIN_WAIT_MINUTES, A_DAY_MINUTES);

        assertThat(find(response, "delta:D3")).isPresent();
        assertThat(find(response, "delta:D2")).isEmpty();
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
        ReachabilityService service = service(GtfsTestFixtures.load(dir, "epsilon"));
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

    // B25: the faster weekend ZT7 is the best arrival at the hub, but the weekday ZT3 can only be reached from ZT1
    @Test
    void transferOnlyBoardsATrainThatRunsOnADayTheArrivingTrainRuns(@TempDir Path dir) throws Exception {
        ReachabilityResponse response = query(GtfsTestFixtures.load(dir, "zeta"), "zeta:Z1", 1, 0, 180);

        ReachableStation weekday = find(response, "zeta:Z4").orElseThrow();
        assertThat(weekday.getTotalDurationMinutes()).isEqualTo(120); // ZT1 08:00, ZT3 09:30, not 90 via ZT7
        assertThat(weekday.getLegs().getFirst().getDepartureTime()).isEqualTo("08:00:00");
        assertThat(find(response, "zeta:Z3")).get()
                .extracting(ReachableStation::getTotalDurationMinutes).isEqualTo(90); // ZT7 08:30, ZT2 09:30
    }

    // B33: ZT6 runs only on 8 weekdays that calendar_dates.txt removes from ZT1's service. The search compares days
    // by weekday and 6-week period, where they meet, so the journey is found but shows that no date fits.
    @Test
    void trainsOnlyRunningOnDaysRemovedFromTheOtherConnectWithAWarning(@TempDir Path dir) throws Exception {
        ReachabilityResponse response = query(GtfsTestFixtures.load(dir, "zeta"), "zeta:Z1", 2, 0, 180);

        ReachableStation holiday = find(response, "zeta:Z6").orElseThrow();
        assertThat(holiday.getHasCommonDay()).isFalse();
        assertThat(holiday.getRunningDays()).isNull();
    }

    // B25: Friday-only ZT4 arrives 23:30, Saturday-only ZT5 leaves 00:30, so they connect across midnight
    @Test
    void transferPastMidnightBoardsATrainRunningTheNextDay(@TempDir Path dir) throws Exception {
        ReachableStation night = find(query(GtfsTestFixtures.load(dir, "zeta"), "zeta:Z1", 1, 0, 180),
                "zeta:Z5").orElseThrow();

        assertThat(night.getTotalDurationMinutes()).isEqualTo(180); // 90 ride + 60 wait + 30 ride
    }

    @Test
    void journeyShowsTheDaysOnWhichAllItsTrainsRun(@TempDir Path dir) throws Exception {
        ReachabilityResponse response = query(GtfsTestFixtures.load(dir, "zeta"), "zeta:Z1", 1, 0, 180);

        assertThat(find(response, "zeta:Z4")).get().extracting(ReachableStation::getRunningDays)
                .isEqualTo("Runs Mon–Fri");
        // Counted from the Friday train; the Saturday one after midnight is a day later
        assertThat(find(response, "zeta:Z5")).get().extracting(ReachableStation::getRunningDays)
                .isEqualTo("Runs Fri");
    }

    // The search keeps one of the twin trips ZT10/ZT11; the journey runs on the days of both
    @Test
    void rideRunsOnTheDaysOfEveryTripWithTheSameTimes(@TempDir Path dir) throws Exception {
        ReachableStation twin = find(query(GtfsTestFixtures.load(dir, "zeta"), "zeta:Z1", 0, 0, 180), "zeta:Z9")
                .orElseThrow();

        assertThat(twin.getRunningDays()).isEqualTo("Runs daily");
    }

    // B33: HT13 is loaded with the regular HT12, but leaves H7 at a time that runs on 2 days only
    @Test
    void rideEndsWhereItsTripLeavesAtATimeThatRunsOnFewDays(@TempDir Path dir) throws Exception {
        ReachabilityResponse response = query(GtfsTestFixtures.load(dir, "eta"), "eta:H1", 0, 0, 180);

        assertThat(find(response, "eta:H7")).isPresent();
        assertThat(find(response, "eta:H8")).isEmpty();
    }

    // B31: on Sat 2026-03-28 only that weekend is left of zeta's services, which end on Sun 2026-03-29
    @Test
    void journeyCountsOnlyItsDaysFromToday(@TempDir Path dir) throws Exception {
        TransitGraph graph = GtfsTestFixtures.load(dir, LocalDate.of(2026, 3, 28), "zeta");
        ReachabilityResponse response = query(graph, "zeta:Z1", 1, 0, 180);

        assertThat(find(response, "zeta:Z9")).get().extracting(ReachableStation::getRunningDays)
                .isEqualTo("Irregular: 2 days in the timetable");
        // ZT1 → ZT3 runs on weekdays only, and none is left
        assertThat(find(response, "zeta:Z4")).isEmpty();
    }

    // B31: zeta's last service day is 2026-03-29
    @Test
    void trainsWhoseDaysAreAllPastAreNotRidden(@TempDir Path dir) throws Exception {
        TransitGraph graph = GtfsTestFixtures.load(dir, LocalDate.of(2026, 10, 6), "zeta");

        assertThat(find(query(graph, "zeta:Z1", 1, 0, 180), "zeta:Z2")).isEmpty();
    }

    // HT2 is 20 min faster than HT1 but runs on 8 days, HT1 on 27
    @Test
    void slightlyFasterTrainThatRunsOnFarFewerDaysLosesToTheRegularOne(@TempDir Path dir) throws Exception {
        ReachableStation end = find(query(GtfsTestFixtures.load(dir, "eta"), "eta:H1", 0, 0, 180), "eta:H2")
                .orElseThrow();

        assertThat(end.getLegs()).extracting(JourneyLeg::getTrainNumber).containsExactly("469");
        assertThat(end.getTotalDurationMinutes()).isEqualTo(120);
        assertThat(end.getRunningDays()).isEqualTo("Runs daily");
    }

    // HT4 runs on 8 days like HT2, but is 70 min faster than the regular HT3
    @Test
    void rareTrainMoreThanHalfAnHourFasterStillWins(@TempDir Path dir) throws Exception {
        ReachableStation far = find(query(GtfsTestFixtures.load(dir, "eta"), "eta:H1", 0, 0, 180), "eta:H3")
                .orElseThrow();

        assertThat(far.getLegs()).extracting(JourneyLeg::getTrainNumber).containsExactly("473");
        assertThat(far.getTotalDurationMinutes()).isEqualTo(110);
    }

    // ZT1 (weekdays, 52 days) is 20 min slower than ZT7 (weekends, 24 days)
    @Test
    void slowerTrainIsShownWhenItRunsOnTwiceAsManyDays(@TempDir Path dir) throws Exception {
        ReachableStation hub = find(query(GtfsTestFixtures.load(dir, "zeta"), "zeta:Z1", 0, 0, 180), "zeta:Z2")
                .orElseThrow();

        assertThat(hub.getTotalDurationMinutes()).isEqualTo(60);
        assertThat(hub.getRunningDays()).isEqualTo("Runs Mon–Fri");
    }

    // B33: weekend ZT7 and daily ZT8 share a day, ZT8 and weekday ZT9 too, but all three share none. The faster
    // ZT7 ride on ZT8 must not cut off the slower weekday ZT1 ride that ZT9 needs.
    @Test
    void journeyWhoseTrainsShareNoDayGivesWayToOneThatRuns(@TempDir Path dir) throws Exception {
        ReachableStation end = find(query(GtfsTestFixtures.load(dir, "zeta"), "zeta:Z1", 2, 0, 180), "zeta:Z8")
                .orElseThrow();

        assertThat(end.getLegs().getFirst().getDepartureTime()).isEqualTo("08:00:00");
        assertThat(end.getTotalDurationMinutes()).isEqualTo(190); // ZT1 08:00, ZT8 09:50, ZT9 10:40 → 11:10
        assertThat(end.getRunningDays()).isEqualTo("Runs Mon–Fri");
    }

    @Test
    void journeyWithoutCalendarDataHasNoRunningDays() {
        ReachableStation b2 = find(query(gtfs, "alpha:A1", 1, 0, 180), "beta:B2").orElseThrow();

        assertThat(b2.getRunningDays()).isNull();
    }

    // F24: waiting 150 min at I2 exceeds the 60 min limit, so the search rides I2 → I3 → I2 instead
    @Test
    void journeyThatPassesAStationTwiceNamesIt(@TempDir Path dir) throws Exception {
        ReachableStation end = find(query(GtfsTestFixtures.load(dir, "iota"), "iota:I1", 3, 0, 60), "iota:I4")
                .orElseThrow();

        assertThat(end.getPathNames()).containsExactly("Iota Start", "Iota Hub", "Iota Loop", "Iota Hub", "Iota End");
        assertThat(end.getRoundTripStation()).isEqualTo("Iota Hub");
    }

    @Test
    void journeyThatPassesEveryStationOnceHasNoRoundTrip(@TempDir Path dir) throws Exception {
        ReachableStation end = find(query(GtfsTestFixtures.load(dir, "iota"), "iota:I1", 3, 0, 180), "iota:I4")
                .orElseThrow();

        assertThat(end.getTransfers()).isEqualTo(1);
        assertThat(end.getRoundTripStation()).isNull();
    }

    @Test
    void doneEventCountsOnlyStationsWithinMaxJourneyTime() {
        // A2 (30 min) and A3/B1 (60 min) fit into 1 h, B2 (120 min) does not
        RecordingEmitter emitter = new RecordingEmitter();
        ReachabilityService service = service(gtfs);
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
        ReachabilityService service = service(gtfs);
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

    // B4 while streaming by round: E6 is first reached in round 1, but only round 2 brings it within the time limit
    @Test
    void streamWithATimeLimitSendsTheSameStationsAsTheResponse(@TempDir Path dir) throws Exception {
        ReachabilityService service = service(GtfsTestFixtures.load(dir, "epsilon"));
        ReachabilityRequest request = ReachabilityRequest.builder()
                .startStationId("epsilon:E1")
                .maxTransfers(2)
                .minTransferTimeMinutes(0)
                .maxTransferTimeMinutes(99999)
                .maxJourneyTimeHours(2.0)
                .build();

        RecordingEmitter emitter = new RecordingEmitter();
        service.calculateReachabilityStreaming(request, emitter);
        List<ReachableStation> expected = service.calculateReachability(request).getReachableStations().stream()
                .filter(station -> !station.getStopId().equals("epsilon:E1"))
                .toList();

        assertThat(emitter.streamedStations())
                .extracting(ReachableStationSummary::getStopId, ReachableStationSummary::getTransfers,
                        ReachableStationSummary::getTotalDurationMinutes)
                .containsExactlyInAnyOrderElementsOf(expected.stream()
                        .map(station -> tuple(station.getStopId(), station.getTransfers(),
                                station.getTotalDurationMinutes()))
                        .toList());
        assertThat(emitter.streamedStopIds()).contains("epsilon:E6");
    }

    @Test
    void streamStopsSearchingWhenTheClientIsGone() {
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs);
        ReachabilityService service = GtfsTestFixtures.reachabilityService(gtfs, cache);
        GoneClientEmitter emitter = new GoneClientEmitter();

        service.calculateReachabilityStreaming(streamRequest("alpha:A1", 3), emitter);

        assertThat(emitter.sendAttempts).isEqualTo(1);
        assertThat(cache.contains(new SearchKey("alpha:A1", 3, 0, 180))).isFalse();
    }

    @Test
    void streamIsTurnedAwayWhenTheSearchQueueIsFull() throws Exception {
        SearchGate gate = new SearchGate(1, 0);
        ReachabilityService service = GtfsTestFixtures.reachabilityService(gtfs,
                GtfsTestFixtures.reachabilityCache(gtfs, gate));
        RecordingEmitter emitter = new RecordingEmitter();

        try (HeldPermit ignored = new HeldPermit(gate)) {
            service.calculateReachabilityStreaming(streamRequest("alpha:A1", 1), emitter);
        }

        assertThat(emitter.error).isInstanceOf(SearchGate.BusyException.class);
        assertThat(emitter.streamedStations()).isEmpty();
    }

    @Test
    void streamThatRunsOutOfMemorySendsAnErrorAndFreesItsPermit() {
        ReachabilityService service = GtfsTestFixtures.reachabilityService(gtfs,
                GtfsTestFixtures.reachabilityCache(gtfs, new SearchGate(1, 0)));
        OutOfMemoryEmitter emitter = new OutOfMemoryEmitter();

        service.calculateReachabilityStreaming(streamRequest("alpha:A1", 3), emitter);

        assertThat(emitter.texts).anyMatch(text -> text.contains("event:error"));
        assertThat(emitter.error).isInstanceOf(OutOfMemoryError.class);
        RecordingEmitter next = new RecordingEmitter();
        service.calculateReachabilityStreaming(streamRequest("alpha:A1", 3), next);
        assertThat(next.error).isNull();
        assertThat(next.done().get("total")).isEqualTo(next.streamedStopIds().size());
    }

    @Test
    void journeyOfAStationIsItsStationInTheResponse() {
        ReachabilityService service = service(gtfs);
        ReachabilityRequest request = ReachabilityRequest.builder()
                .startStationId("alpha:A1")
                .maxTransfers(1)
                .minTransferTimeMinutes(0)
                .maxTransferTimeMinutes(180)
                .build();

        ReachableStation journey = service.journey(request, "beta:B2").orElseThrow();

        assertThat(journey).usingRecursiveComparison()
                .isEqualTo(find(service.calculateReachability(request), "beta:B2").orElseThrow());
    }

    @Test
    void journeyToAStationOutOfReachOrToTheStartIsEmpty() {
        ReachabilityService service = service(gtfs);
        ReachabilityRequest request = ReachabilityRequest.builder()
                .startStationId("alpha:A1")
                .maxTransfers(0)
                .minTransferTimeMinutes(0)
                .maxTransferTimeMinutes(180)
                .build();

        assertThat(service.journey(request, "beta:B2")).isEmpty();
        assertThat(service.journey(request, "alpha:A1")).isEmpty();
    }

    /** Captures the data objects of every sent SSE event instead of writing them to a response. */
    private static class RecordingEmitter extends SseEmitter {
        final List<Object> payloads = new ArrayList<>();
        Throwable error;

        @Override
        public void send(SseEventBuilder event) {
            event.build().stream().map(DataWithMediaType::getData)
                    .filter(data -> !(data instanceof String))
                    .forEach(payloads::add);
        }

        @Override
        public void completeWithError(Throwable ex) {
            error = ex;
        }

        List<ReachableStationSummary> streamedStations() {
            return payloads.stream()
                    .filter(List.class::isInstance)
                    .flatMap(batch -> ((List<?>) batch).stream())
                    .map(ReachableStationSummary.class::cast)
                    .toList();
        }

        List<String> streamedStopIds() {
            return streamedStations().stream().map(ReachableStationSummary::getStopId).toList();
        }

        Map<?, ?> done() {
            return payloads.stream()
                    .filter(Map.class::isInstance).map(Map.class::cast)
                    .findFirst().orElseThrow();
        }
    }

    /** Sending the first stations throws OutOfMemoryError, as an allocation of the search would. */
    private static class OutOfMemoryEmitter extends RecordingEmitter {
        final List<String> texts = new ArrayList<>();

        @Override
        public void send(SseEventBuilder event) {
            String text = event.build().stream().map(DataWithMediaType::getData)
                    .filter(String.class::isInstance).map(String.class::cast)
                    .collect(Collectors.joining());
            if (text.contains("event:stations")) throw new OutOfMemoryError("Java heap space");
            texts.add(text);
        }
    }

    /** A client that closed the page: every send fails. */
    private static class GoneClientEmitter extends SseEmitter {
        int sendAttempts;

        @Override
        public void send(SseEventBuilder event) throws IOException {
            sendAttempts++;
            throw new IOException("Broken pipe");
        }
    }

    private static ReachabilityRequest streamRequest(String start, int maxTransfers) {
        return ReachabilityRequest.builder()
                .startStationId(start)
                .maxTransfers(maxTransfers)
                .minTransferTimeMinutes(0)
                .maxTransferTimeMinutes(180)
                .build();
    }

    private static ReachabilityService service(TransitGraph graph) {
        return GtfsTestFixtures.reachabilityService(graph);
    }

    private ReachabilityResponse query(TransitGraph data, String start, int maxTransfers,
                                              int minTransferMins, int maxTransferMins) {
        return service(data).calculateReachability(ReachabilityRequest.builder()
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
