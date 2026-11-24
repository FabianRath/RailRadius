package com.railradius.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.railradius.service.GtfsTestFixtures.VISITOR;
import static com.railradius.service.HeldPermit.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchAdmissionTest {

    private static final String START_ID = "alpha:A1";
    private static final int MAX_TRANSFERS = 3;
    // The cost of the biggest search (5 transfers, 360-min wait), the least budget a client can have
    private static final int BIG_SEARCH_COST = 87;
    private static final SearchOptions BIG_SEARCH = new SearchOptions(START_ID, 5, 0, 360);
    private static final SearchAdmission.Client ADMIN = new SearchAdmission.Client(VISITOR.ip(), true);

    @TempDir
    static Path tempDir;

    static TransitGraph gtfs;

    @BeforeAll
    static void loadFixture() throws Exception {
        gtfs = GtfsTestFixtures.load(tempDir, "alpha", "beta");
    }

    @Test
    void aStoredResultCostsNoBudget() {
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs);
        SearchAdmission admission = admissionWithBudgetForOneBigSearch(cache);
        admission.admit(VISITOR, BIG_SEARCH, SearchAdmission.SearchListener.NONE);

        assertThatCode(() -> {
            admission.admit(VISITOR, BIG_SEARCH, SearchAdmission.SearchListener.NONE);
            admission.admit(VISITOR, BIG_SEARCH.withMaxTransfers(1), SearchAdmission.SearchListener.NONE);
        }).doesNotThrowAnyException();
    }

    // #16: the charge follows the lookup that serves the result, so a result that is gone is searched and charged
    @Test
    void aSearchWhoseStoredResultWasEvictedIsCharged() {
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs, 1);
        SearchAdmission admission = admissionWithBudgetForOneBigSearch(cache);
        admission.admit(VISITOR, BIG_SEARCH, SearchAdmission.SearchListener.NONE);
        admission.admit(ADMIN, key(0), SearchAdmission.SearchListener.NONE);

        assertThatThrownBy(() -> admission.admit(VISITOR, BIG_SEARCH, SearchAdmission.SearchListener.NONE))
                .isInstanceOfSatisfying(SearchRefusal.class, refusal -> assertThat(refusal.refusalReason())
                        .isEqualTo(SearchRefusal.Reason.TOO_MANY_SEARCHES));
    }

    // S37: a joiner searches itself if the one it waits for stops, so waiting is not free
    @Test
    void aSearchThatJoinsAnEqualRunningOneIsCharged() throws Exception {
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs);
        SearchAdmission admission = admissionWithBudgetForOneBigSearch(cache);
        HeldSearch first = new HeldSearch(admission, BIG_SEARCH, () -> {});

        assertThatThrownBy(() -> admission.admit(VISITOR, BIG_SEARCH, SearchAdmission.SearchListener.NONE))
                .isInstanceOfSatisfying(SearchRefusal.class, refusal -> assertThat(refusal.refusalReason())
                        .isEqualTo(SearchRefusal.Reason.TOO_MANY_SEARCHES));
        first.release();
        first.task.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void aSearchBeyondTheFullQueueIsRefusedAsBusy() throws Exception {
        SearchGate gate = GtfsTestFixtures.gate(1, 0);
        SearchAdmission admission = GtfsTestFixtures.admission(gtfs, GtfsTestFixtures.reachabilityCache(gtfs), gate);

        try (HeldPermit ignored = new HeldPermit(gate)) {
            assertThatThrownBy(() -> admission.admit(VISITOR, key(1), SearchAdmission.SearchListener.NONE))
                    .isInstanceOfSatisfying(SearchRefusal.class, refusal -> assertThat(refusal.refusalReason())
                            .isEqualTo(SearchRefusal.Reason.BUSY));
        }
    }

    @Test
    void aSearchPastItsDeadlineIsRefusedAsTooSlowStoresNothingAndFreesItsPermit() {
        SearchGate gate = GtfsTestFixtures.gate(1, 0);
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs);
        SearchAdmission admission = GtfsTestFixtures.admission(gtfs, cache, gate,
                GtfsTestFixtures.unlimitedRateLimiter(), Duration.ZERO);

        assertThatThrownBy(() -> admission.admit(VISITOR, key(MAX_TRANSFERS), SearchAdmission.SearchListener.NONE))
                .isInstanceOfSatisfying(SearchRefusal.class, refusal -> assertThat(refusal.refusalReason())
                        .isEqualTo(SearchRefusal.Reason.TOO_SLOW));
        assertThat(cache.contains(key(MAX_TRANSFERS))).isFalse();
        assertThat(gate.runIfFree(() -> true)).isPresent();
    }

    // The 6th of 6 queued searches on 2 permits waits for 3 waves of searches, then runs its own
    @Test
    void longestWaitIsTheFullQueuesWavesPlusTheSearchItself() {
        Duration deadline = Duration.ofSeconds(30);

        assertThat(admissionWithGate(2, 6, deadline).longestWait()).isEqualTo(Duration.ofSeconds(120));
        assertThat(admissionWithGate(2, 5, deadline).longestWait()).isEqualTo(Duration.ofSeconds(120));
        assertThat(admissionWithGate(2, 0, deadline).longestWait()).isEqualTo(deadline);
    }

    // S37
    @Test
    void anEqualSearchStartedMeanwhileWaitsWithoutAPermitAndGetsTheSameResult() throws Exception {
        SearchGate gate = GtfsTestFixtures.gate(2, 0);
        SearchAdmission admission = GtfsTestFixtures.admission(gtfs, GtfsTestFixtures.reachabilityCache(gtfs), gate);
        HeldSearch first = new HeldSearch(admission, key(MAX_TRANSFERS), () -> {});
        Waiter second = new Waiter(admission);

        second.awaitWaiting();
        // The first search holds one of the two permits, so the waiting one holds none
        assertThat(gate.runIfFree(() -> true)).isPresent();
        first.release();

        CompactResult result = first.task.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(second.task.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isSameAs(result);
        assertThat(second.searched).isFalse();
    }

    // S37
    @Test
    void aSearchWaitingForOneWhoseClientLeftSearchesItself() throws Exception {
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs);
        SearchAdmission admission = GtfsTestFixtures.admission(gtfs, cache);
        HeldSearch first = new HeldSearch(admission, key(MAX_TRANSFERS), () -> {
            throw new CancellationException("the client is gone");
        });
        Waiter second = new Waiter(admission);

        second.awaitWaiting();
        first.release();

        assertThatThrownBy(() -> first.task.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .hasCauseInstanceOf(CancellationException.class);
        CompactResult result = second.task.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(second.searched).isTrue();
        assertThat(result.stationCount()).isEqualTo(searchedAlone().stationCount());
        assertThat(cache.contains(key(MAX_TRANSFERS))).isTrue();
    }

    // S37
    @Test
    void aSearchWaitingForOneThatFailsGetsTheSameFailure() throws Exception {
        SearchAdmission admission = GtfsTestFixtures.admission(gtfs, GtfsTestFixtures.reachabilityCache(gtfs));
        HeldSearch first = new HeldSearch(admission, key(MAX_TRANSFERS), () -> {
            throw new IllegalStateException("search failed");
        });
        Waiter second = new Waiter(admission);

        second.awaitWaiting();
        first.release();

        assertThatThrownBy(() -> second.task.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class).hasRootCauseMessage("search failed");
        assertThat(second.searched).isFalse();
        assertThat(admission.admit(VISITOR, key(MAX_TRANSFERS), SearchAdmission.SearchListener.NONE).stationCount())
                .isEqualTo(searchedAlone().stationCount());
    }

    // S37
    @Test
    void aWaitingSearchWhoseClientLeftStopsWithoutStoppingTheOneItWaitedFor() throws Exception {
        SearchAdmission admission = GtfsTestFixtures.admission(gtfs, GtfsTestFixtures.reachabilityCache(gtfs));
        HeldSearch first = new HeldSearch(admission, key(MAX_TRANSFERS), () -> {});

        assertThatThrownBy(() -> admission.admit(VISITOR, key(MAX_TRANSFERS), new SearchAdmission.SearchListener() {
            @Override
            public void queued() {
                throw new CancellationException("the client is gone");
            }
        })).isInstanceOf(CancellationException.class);
        first.release();

        assertThat(first.task.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).stationCount())
                .isEqualTo(searchedAlone().stationCount());
    }

    // S37: the warmer never waits for a visitor's search
    @Test
    void warmingSkipsSearchOptionsThatAreBeingSearched() throws Exception {
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs);
        SearchAdmission admission = GtfsTestFixtures.admission(gtfs, cache, GtfsTestFixtures.gate(2, 0));
        HeldSearch first = new HeldSearch(admission, key(MAX_TRANSFERS), () -> {});

        assertThat(admission.warmIfIdle(key(MAX_TRANSFERS))).isFalse();
        first.release();

        first.task.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(cache.contains(key(MAX_TRANSFERS))).isTrue();
    }

    // A clock that stands still, so the budget never refills during the test
    private static SearchAdmission admissionWithBudgetForOneBigSearch(ReachabilityCache cache) {
        return GtfsTestFixtures.admission(gtfs, cache, GtfsTestFixtures.gate(2, 6),
                new SearchRateLimiter(BIG_SEARCH_COST, 1, () -> 0L), CompactReachabilitySearch.NO_DEADLINE);
    }

    private static SearchAdmission admissionWithGate(int permits, int maxQueued, Duration deadline) {
        return GtfsTestFixtures.admission(gtfs, GtfsTestFixtures.reachabilityCache(gtfs),
                GtfsTestFixtures.gate(permits, maxQueued), GtfsTestFixtures.unlimitedRateLimiter(), deadline);
    }

    private static CompactResult searchedAlone() {
        return GtfsTestFixtures.search(gtfs).search(START_ID, MAX_TRANSFERS, 0, 180,
                CompactReachabilitySearch.NO_DEADLINE, roundPaths -> {});
    }

    /** A search on another thread that stops after its first round until released, then runs afterRelease. */
    private static final class HeldSearch {
        final FutureTask<CompactResult> task;
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        HeldSearch(SearchAdmission admission, SearchOptions options, Runnable afterRelease)
                throws InterruptedException {
            task = new FutureTask<>(() -> admission.admit(VISITOR, options, new SearchAdmission.SearchListener() {
                @Override
                public void roundPaths(Map<String, CompactResult.PathSummary> paths) {
                    if (held.getCount() == 0) return;
                    held.countDown();
                    awaitLatch(release);
                    afterRelease.run();
                }
            }));
            Thread.ofVirtual().start(task);
            assertThat(held.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).as("search running").isTrue();
        }

        void release() {
            release.countDown();
        }
    }

    /** An equal search on another thread, started while a {@link HeldSearch} runs. */
    private static final class Waiter {
        final FutureTask<CompactResult> task;
        final AtomicBoolean searched = new AtomicBoolean();
        private final CountDownLatch waiting = new CountDownLatch(1);

        Waiter(SearchAdmission admission) {
            task = new FutureTask<>(() -> admission.admit(VISITOR, key(MAX_TRANSFERS),
                    new SearchAdmission.SearchListener() {
                        @Override
                        public void queued() {
                            waiting.countDown();
                        }

                        @Override
                        public void roundPaths(Map<String, CompactResult.PathSummary> paths) {
                            searched.set(true);
                        }
                    }));
            Thread.ofVirtual().start(task);
        }

        // The gate's permits are not all taken, so a tick means it waits for the equal search
        void awaitWaiting() throws InterruptedException {
            assertThat(waiting.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).as("waiting").isTrue();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static SearchOptions key(int transfers) {
        return new SearchOptions(START_ID, transfers, 0, 180);
    }
}
