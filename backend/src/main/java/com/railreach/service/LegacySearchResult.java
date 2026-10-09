package com.railreach.service;

import com.railreach.model.JourneyPath;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** The legacy search's result: its {@link JourneyPath}s per station. Deleted with the legacy engine (S21). */
record LegacySearchResult(Map<String, List<JourneyPath>> pathsByStation, TransitGraph graph) implements SearchResult {

    @Override
    public void forEachStation(BiConsumer<String, List<PathSummary>> action) {
        pathsByStation.forEach((stationId, paths) -> action.accept(stationId, summaries(paths)));
    }

    @Override
    public List<PathSummary> paths(String stationId) {
        return summaries(pathsByStation.getOrDefault(stationId, List.of()));
    }

    @Override
    public Journey journey(String stationId, int transfers) {
        JourneyPath path = pathsByStation.getOrDefault(stationId, List.of()).stream()
                .filter(p -> p.getTransfers() == transfers)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No path with " + transfers + " transfers to " + stationId));
        return new Journey(path, JourneyRunningDays.of(path.getConnections(), graph));
    }

    @Override
    public SearchResult withMaxTransfers(int maxTransfers) {
        Map<String, List<JourneyPath>> limited = new HashMap<>(pathsByStation.size() * 2);
        pathsByStation.forEach((stationId, paths) -> {
            List<JourneyPath> within = paths.stream().filter(path -> path.getTransfers() <= maxTransfers).toList();
            if (!within.isEmpty()) limited.put(stationId, within);
        });
        return new LegacySearchResult(limited, graph);
    }

    @Override
    public int stationCount() {
        return pathsByStation.size();
    }

    static PathSummary summary(JourneyPath path) {
        return new PathSummary(path.getTransfers(), path.getTotalDurationMinutes());
    }

    private static List<PathSummary> summaries(List<JourneyPath> paths) {
        return paths.stream().map(LegacySearchResult::summary).toList();
    }
}
