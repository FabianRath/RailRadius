package com.railreach.service;

import com.railreach.model.Connection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CachedTripTest {

    @Test
    void connectionMatchingNoLegOfItsTripIsLeftOut() {
        Connection onTrip = connection("x:A", "x:B", 100, 200);
        Connection offTrip = connection("x:A", "x:Z", 100, 300);

        CachedTrip trip = CachedTrip.of(List.of("x:A", "x:B", "x:C"), new TripInfo(null, null, null), null,
                List.of(offTrip, onTrip));
        TransitGraph graph = new TransitGraph();
        trip.addTo("x:T", graph);

        assertThat(trip.connectionCount()).isEqualTo(1);
        assertThat(graph.getConnections("x:A", "x:B")).containsExactly(onTrip);
        assertThat(graph.getNeighbors("x:A")).containsExactly("x:B");
    }

    private static Connection connection(String fromId, String toId, int departureSeconds, int arrivalSeconds) {
        return Connection.builder().tripId("x:T").routeName("R").fromStopId(fromId).toStopId(toId)
                .departureTimeSeconds(departureSeconds).arrivalTimeSeconds(arrivalSeconds).build();
    }
}
