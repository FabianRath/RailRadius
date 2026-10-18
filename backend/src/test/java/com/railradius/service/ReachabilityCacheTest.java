package com.railradius.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.railradius.service.HeldPermit.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReachabilityCacheTest {

    private static final String START_ID = "alpha:A1";
    private static final int MAX_TRANSFERS = 3;

    @TempDir
    static Path tempDir;

    static TransitGraph gtfs;

    @BeforeAll
    static void loadFixture() throws Exception {
        gtfs = GtfsTestFixtures.load(tempDir, "alpha", "beta");
    }

    // B20, B21: a shortcut must return exactly what the search returns
    @Test
    void lowerTransferCountsCutFromAStoredResultEqualTheirOwnSearch() {
        CompactReachabilitySearch search = GtfsTestFixtures.search(gtfs);
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs);
        cache.getOrCompute(key(MAX_TRANSFERS));

        for (int transfers = 0; transfers < MAX_TRANSFERS; transfers++) {
            CompactResult cutDown = cache.getOrCompute(key(transfers));
            CompactResult searched = search.search(START_ID, transfers, 0, 180, roundPaths -> {});
            assertThat(cache.contains(key(transfers))).isTrue();
            assertThat(GtfsTestFixtures.journeys(cutDown)).as("%d transfers", transfers)
                    .isEqualTo(GtfsTestFixtures.journeys(searched));
            assertThat(cutDown.stationCount()).isEqualTo(searched.stationCount());
        }
        assertThat(cache.getStats()).containsEntry("rawPathCacheEntries", 1L);
    }

    // B9
    @Test
    void storedResultsBeyondTheMaximumAreEvicted() {
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs, 1);

        cache.getOrCompute(key(0));
        cache.getOrCompute(key(1));

        assertThat(cache.getStats()).containsEntry("rawPathCacheEntries", 1L);
    }

    // B9
    @Test
    void reloadingGtfsDropsStoredResults(@TempDir Path dir) throws Exception {
        TransitGraph graph = new TransitGraph();
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(graph);
        GtfsDataService dataService = GtfsTestFixtures.dataService(dir, graph, cache, new StationSearchIndex(graph),
                "alpha", "beta");
        dataService.loadFromFiles();
        cache.getOrCompute(key(1));

        dataService.loadFromFiles();

        assertThat(cache.contains(key(1))).isFalse();
    }

    // S37
    @Test
    void anEqualSearchStartedMeanwhileWaitsWithoutAPermitAndGetsTheSameResult() throws Exception {
        SearchGate gate = new SearchGate(2, 0);
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs, gate);
        HeldSearch first = new HeldSearch(cache, () -> {});
        Waiter second = new Waiter(cache);

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
        HeldSearch first = new HeldSearch(cache, () -> {
            throw new CancellationException("the client is gone");
        });
        Waiter second = new Waiter(cache);

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
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs);
        HeldSearch first = new HeldSearch(cache, () -> {
            throw new IllegalStateException("search failed");
        });
        Waiter second = new Waiter(cache);

        second.awaitWaiting();
        first.release();

        assertThatThrownBy(() -> second.task.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class).hasRootCauseMessage("search failed");
        assertThat(second.searched).isFalse();
        assertThat(cache.getOrCompute(key(MAX_TRANSFERS)).stationCount())
                .isEqualTo(searchedAlone().stationCount());
    }

    // S37
    @Test
    void aWaitingSearchWhoseClientLeftStopsWithoutStoppingTheOneItWaitedFor() throws Exception {
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs);
        HeldSearch first = new HeldSearch(cache, () -> {});

        assertThatThrownBy(() -> cache.getOrCompute(key(MAX_TRANSFERS), new ReachabilityCache.SearchListener() {
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
    void computeIfPermitFreeSkipsAKeyThatIsBeingSearched() throws Exception {
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs, new SearchGate(2, 0));
        HeldSearch first = new HeldSearch(cache, () -> {});

        assertThat(cache.computeIfPermitFree(key(MAX_TRANSFERS))).isFalse();
        first.release();

        first.task.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(cache.contains(key(MAX_TRANSFERS))).isTrue();
    }

    private static CompactResult searchedAlone() {
        return GtfsTestFixtures.search(gtfs).search(START_ID, MAX_TRANSFERS, 0, 180, roundPaths -> {});
    }

    /** A search on another thread that stops after its first round until released, then runs afterRelease. */
    private static final class HeldSearch {
        final FutureTask<CompactResult> task;
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        HeldSearch(ReachabilityCache cache, Runnable afterRelease) throws InterruptedException {
            task = new FutureTask<>(() -> cache.getOrCompute(key(MAX_TRANSFERS),
                    new ReachabilityCache.SearchListener() {
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

        Waiter(ReachabilityCache cache) {
            task = new FutureTask<>(() -> cache.getOrCompute(key(MAX_TRANSFERS),
                    new ReachabilityCache.SearchListener() {
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

    private static ReachabilityCache.SearchKey key(int transfers) {
        return new ReachabilityCache.SearchKey(START_ID, transfers, 0, 180);
    }
}
