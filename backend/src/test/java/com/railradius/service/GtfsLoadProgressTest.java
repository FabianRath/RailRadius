package com.railradius.service;

import com.railradius.dto.GtfsLoadStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GtfsLoadProgressTest {

    private static final int FEED_SIZE_BYTES = 1000;

    @Test
    void statusIsIdleBeforeAnyLoad() {
        GtfsLoadStatus status = new GtfsLoadProgress().status(0, 0);

        assertThat(status.getPhase()).isEqualTo("IDLE");
        assertThat(status.getElapsedSeconds()).isZero();
        assertThat(status.getParseEtaSeconds()).isNull();
    }

    @Test
    void finishedLoadReportsEveryFeedParsedAndTheGraphSize(@TempDir Path dir) throws Exception {
        TransitGraph graph = new TransitGraph();
        GtfsDataService service = GtfsTestFixtures.dataService(dir, graph, GtfsTestFixtures.reachabilityCache(graph),
                new StationSearchIndex(graph), "alpha", "beta");

        service.loadFromFiles();
        GtfsLoadStatus status = service.getLoadStatus();

        assertThat(status.getPhase()).isEqualTo("DONE");
        assertThat(status.getFeedsDone()).isEqualTo(2).isEqualTo(status.getFeedsTotal());
        assertThat(status.getParsedPercent()).isEqualTo(100.0);
        assertThat(status.getCurrentFeed()).isNull();
        assertThat(status.getParseEtaSeconds()).isNull();
        assertThat(status.getStationCount()).isEqualTo(graph.stationCount()).isPositive();
    }

    @Test
    void loadWithoutFeedsEndsAsFailed(@TempDir Path dir) throws Exception {
        TransitGraph graph = new TransitGraph();
        GtfsDataService service = GtfsTestFixtures.dataService(dir, graph, GtfsTestFixtures.reachabilityCache(graph),
                new StationSearchIndex(graph));

        assertThatThrownBy(service::loadFromFiles).isInstanceOf(IllegalStateException.class);
        assertThat(service.getLoadStatus().getPhase()).isEqualTo("FAILED");
    }

    @Test
    void parseProgressAndEtaFollowTheZipBytesDone(@TempDir Path dir) throws Exception {
        File small = feedOfSize(dir, "gtfs_small.zip", FEED_SIZE_BYTES);
        File large = feedOfSize(dir, "gtfs_large.zip", 3 * FEED_SIZE_BYTES);
        GtfsLoadProgress progress = new GtfsLoadProgress();
        progress.start();
        progress.feedsFound(new File[] {small, large});

        progress.feedStarted(small);
        progress.feedFinished(small);
        progress.feedStarted(large);
        GtfsLoadStatus status = progress.status(0, 0);

        assertThat(status.getPhase()).isEqualTo("PARSING");
        assertThat(status.getFeedsDone()).isEqualTo(1);
        assertThat(status.getCurrentFeed()).isEqualTo("gtfs_large.zip");
        assertThat(status.getParsedPercent()).isEqualTo(25.0);
        assertThat(status.getParseEtaSeconds()).isNotNull();
    }

    @Test
    void mergingPhaseHasNoCurrentFeedOrEta(@TempDir Path dir) throws Exception {
        File feed = feedOfSize(dir, "gtfs_only.zip", FEED_SIZE_BYTES);
        GtfsLoadProgress progress = new GtfsLoadProgress();
        progress.start();
        progress.feedsFound(new File[] {feed});
        progress.feedStarted(feed);
        progress.feedFinished(feed);

        progress.enter(GtfsLoadProgress.Phase.MERGING);
        GtfsLoadStatus status = progress.status(0, 0);

        assertThat(status.getCurrentFeed()).isNull();
        assertThat(status.getParseEtaSeconds()).isNull();
    }

    private static File feedOfSize(Path dir, String name, int sizeBytes) throws Exception {
        return Files.write(dir.resolve(name), new byte[sizeBytes]).toFile();
    }
}
