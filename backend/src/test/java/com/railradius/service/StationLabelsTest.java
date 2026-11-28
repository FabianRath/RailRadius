package com.railradius.service;

import com.railradius.dto.StationLabel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StationLabelsTest {

    @Test
    void everyStationIsLabelledWithItsNameCoordinatesAndEdgeCount(@TempDir Path dir) throws Exception {
        TransitGraph graph = GtfsTestFixtures.load(dir, "alpha", "beta");

        List<StationLabel> labels = StationLabels.of(graph);

        assertThat(labels).hasSize(graph.getAllStations().size());
        graph.getAllStations().values().forEach(station -> assertThat(labels).contains(new StationLabel(
                station.getName(), station.getLatitude(), station.getLongitude(),
                graph.nextStationCount(station.getId()))));
        assertThat(labels).anyMatch(label -> label.getImportance() > 0);
    }

    @Test
    void anEmptyGraphHasNoLabels() {
        assertThat(StationLabels.of(new TransitGraph())).isEmpty();
    }
}
