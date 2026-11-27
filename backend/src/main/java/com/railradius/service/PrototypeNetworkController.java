package com.railradius.service;

import com.railradius.model.Station;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PROTOTYPE (branch prototype/redesign, never merged): the served rail network as GeoJSON, so the redesign
 * prototype can draw its own basemap. Edges join consecutive stops; an edge that another trip covers with stops
 * in between (an express skipping stations) is dropped, so the map shows track-like links, not chords.
 */
@RestController
@RequiredArgsConstructor
class PrototypeNetworkController {

    private static final int SKIP_WINDOW = 12;

    private final TransitGraph transitGraph;

    @GetMapping("/api/prototype/network")
    Map<String, Object> network() {
        TimetableStore store = transitGraph.timetableStore();
        Map<Long, Integer> tripsByEdge = new HashMap<>();
        Set<Long> skipped = new HashSet<>();
        for (int trip = 0; trip < store.tripCount(); trip++) {
            int first = store.firstEvent(trip);
            int end = store.endEvent(trip);
            for (int event = first; event + 1 < end; event++) {
                tripsByEdge.merge(key(store.station(event), store.station(event + 1)), 1, Integer::sum);
                for (int later = event + 2; later < Math.min(end, event + SKIP_WINDOW); later++) {
                    skipped.add(key(store.station(event), store.station(later)));
                }
            }
        }
        List<Map<String, Object>> features = new ArrayList<>();
        tripsByEdge.forEach((edge, trips) -> {
            if (skipped.contains(edge)) return;
            Station a = transitGraph.getStation(store.stations().id((int) (edge >> 32)));
            Station b = transitGraph.getStation(store.stations().id((int) (long) edge));
            if (!located(a) || !located(b)) return;
            features.add(Map.of("type", "Feature", "properties", Map.of("trips", trips),
                    "geometry", Map.of("type", "LineString", "coordinates",
                            List.of(List.of(a.getLongitude(), a.getLatitude()),
                                    List.of(b.getLongitude(), b.getLatitude())))));
        });
        for (int index = 0; index < store.stations().size(); index++) {
            Station station = transitGraph.getStation(store.stations().id(index));
            int trips = store.stoppingTripCount(index);
            if (!located(station) || trips == 0) continue;
            features.add(Map.of("type", "Feature",
                    "properties", Map.of("name", station.getName(), "trips", trips),
                    "geometry", Map.of("type", "Point",
                            "coordinates", List.of(station.getLongitude(), station.getLatitude()))));
        }
        return Map.of("type", "FeatureCollection", "features", features);
    }

    private static long key(int stationA, int stationB) {
        int low = Math.min(stationA, stationB);
        int high = Math.max(stationA, stationB);
        return ((long) low << 32) | high;
    }

    private static boolean located(Station station) {
        return station != null && station.getLatitude() != null && station.getLongitude() != null;
    }
}
