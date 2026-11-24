package com.railradius.service;

import com.railradius.dto.ReachableStationSummary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The stations a search shows as its rounds end: each once, with its first path within the max journey time, never
 * the start station. The server's stream and the browser engine (#12) both pick them here, so they show the same.
 */
public final class ShownStations {

    private final String startId;
    private final int maxJourneyMins;
    private final JourneyLegBuilder legBuilder;
    private final Set<String> shown = new HashSet<>();

    public ShownStations(String startId, int maxJourneyMins, JourneyLegBuilder legBuilder) {
        this.startId = startId;
        this.maxJourneyMins = maxJourneyMins;
        this.legBuilder = legBuilder;
    }

    /** A round's stations not shown yet, described by the graph the search runs on. */
    public List<ReachableStationSummary> newInRound(ServedGraph served,
                                                    Map<String, CompactResult.PathSummary> roundPaths) {
        List<ReachableStationSummary> stations = new ArrayList<>();
        roundPaths.forEach((stopId, path) -> addIfNew(served, stations, stopId, List.of(path)));
        return stations;
    }

    /** A result's stations not shown yet: all of a stored result, or those a round could not describe. */
    public List<ReachableStationSummary> newInResult(CompactResult result) {
        List<ReachableStationSummary> stations = new ArrayList<>();
        result.forEachStation((stopId, paths) -> addIfNew(result.served(), stations, stopId, paths));
        return stations;
    }

    public int count() {
        return shown.size();
    }

    // The paths are ordered by ascending transfers, so a station whose fewest-transfer path is too long still shows
    // up with a faster path that has more transfers (B4)
    static Optional<CompactResult.PathSummary> fewestTransfersWithin(List<CompactResult.PathSummary> paths,
                                                                    int maxJourneyMins) {
        return paths.stream().filter(path -> path.durationMinutes() <= maxJourneyMins).findFirst();
    }

    private void addIfNew(ServedGraph served, List<ReachableStationSummary> stations, String stopId,
                          List<CompactResult.PathSummary> paths) {
        if (stopId.equals(startId) || shown.contains(stopId)) return;
        fewestTransfersWithin(paths, maxJourneyMins)
                .flatMap(path -> legBuilder.toSummary(served, stopId, path))
                .ifPresent(station -> {
                    stations.add(station);
                    shown.add(stopId);
                });
    }
}
