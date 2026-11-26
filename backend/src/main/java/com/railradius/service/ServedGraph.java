package com.railradius.service;

import com.railradius.model.Station;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The served graph: one snapshot of the merged rail network that the app answers from. A load replaces it whole
 * ({@link TransitGraph#serve}), so a reader that holds one never sees it change. All IDs are canonical stop/trip IDs.
 *
 * @param translatedNames stop ID -> its names in other languages, from the feeds' translations.txt
 * @param tripInfos train number, headsign and operator of each store trip; none on a browser's graph
 * @param feeds how long each feed runs, sorted by source
 */
record ServedGraph(Map<String, Station> stations, Map<String, Set<String>> translatedNames,
                   TripInfos tripInfos, TimetableStore store, List<FeedTimespan> feeds,
                   StationSearchIndex.Index searchIndex) {

    static final ServedGraph EMPTY = new ServedGraph(Map.of(), Map.of(), TripInfos.EMPTY, TimetableStore.empty(),
            List.of(), StationSearchIndex.Index.EMPTY);

    static ServedGraph of(BinaryCacheStore.BinaryCacheData data) {
        Map<String, Station> stations = new HashMap<>();
        data.stations().forEach(station -> stations.put(station.getId(), station));
        TimetableStore store = data.store();
        Map<String, Set<String>> translatedNames = Collections.unmodifiableMap(data.translatedNames());
        return new ServedGraph(Collections.unmodifiableMap(stations), translatedNames, data.tripInfos(), store,
                data.feeds(), StationSearchIndex.build(stations.values(), translatedNames, store));
    }

    /**
     * The graph a browser searches on: no translated names, trip infos, feeds or name index, which the server keeps.
     * It builds no name index, so the browser engine never reaches the index's patterns, which it cannot compile.
     */
    static ServedGraph of(BrowserDataFile.BrowserData data) {
        Map<String, Station> stations = new HashMap<>();
        data.stations().forEach(station -> stations.put(station.getId(), station));
        return new ServedGraph(Collections.unmodifiableMap(stations), Map.of(), TripInfos.EMPTY, data.store(),
                List.of(), StationSearchIndex.Index.EMPTY);
    }

    Station station(String stopId) {
        return stations.get(stopId);
    }

    Set<String> translatedNamesOf(String stopId) {
        return translatedNames.getOrDefault(stopId, Set.of());
    }

    /** Empty for a trip the graph has no info of, as on a browser's graph. */
    Optional<TripInfo> tripInfo(int trip) {
        return trip < tripInfos.size() ? Optional.of(tripInfos.get(trip)) : Optional.empty();
    }
}
