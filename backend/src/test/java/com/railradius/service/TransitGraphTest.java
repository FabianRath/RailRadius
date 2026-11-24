package com.railradius.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TransitGraphTest {

    // #18: a load replaces the served graph whole, so a reader never sees the old graph and the new one mixed
    @Test
    void servingNewDataLeavesTheServedGraphFromBeforeWhole(@TempDir Path dir) throws Exception {
        TransitGraph graph = TransitGraph.withToday(GtfsTestFixtures.TODAY);
        graph.serve(GtfsTestFixtures.network(dir.resolve("alpha"), "alpha").servedData());
        ServedGraph before = graph.served();

        graph.serve(GtfsTestFixtures.network(dir.resolve("omega"), "omega").servedData());

        assertThat(before.station("alpha:A1")).isNotNull();
        assertThat(before.tripInfo("alpha:T1")).isPresent();
        assertThat(before.store().stations().indexOf("alpha:A1")).isPresent();
        assertThat(before.searchIndex().ranked()).extracting("id").contains("alpha:A1");
        assertThat(graph.getStation("alpha:A1")).isNull();
        assertThat(graph.getStation("omega:O1")).isNotNull();
        assertThat(graph.served().searchIndex().ranked()).extracting("id").doesNotContain("alpha:A1");
    }
}
