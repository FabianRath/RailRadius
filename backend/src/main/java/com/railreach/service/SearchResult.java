package com.railreach.service;

import com.railreach.model.JourneyPath;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * What a search found: per station its paths by ascending transfers ({@link SearchEngine#search}). A path's transfers
 * and duration are at hand; its stations, connections and running days are built on demand ({@link #journey}), so a
 * stored compact result holds no objects per path (S19). Two implementations until the legacy engine goes (S21):
 * {@link LegacySearchResult} and {@link CompactResult}.
 */
public interface SearchResult {

    /** A path's transfers and duration, which is all the stream sends of it. */
    record PathSummary(int transfers, int durationMinutes) {}

    /** A path's stations and connections, and the exact days on which the whole journey runs. */
    record Journey(JourneyPath path, ServiceDays runningDays) {}

    /** Calls the action with every station that has a path and its paths; the start station has its one empty path. */
    void forEachStation(BiConsumer<String, List<PathSummary>> action);

    /** The station's paths by ascending transfers; empty if it has none. */
    List<PathSummary> paths(String stationId);

    /**
     * The station's path with this many transfers, one of {@link #paths}.
     *
     * @throws IllegalArgumentException if the station has no such path
     */
    Journey journey(String stationId, int transfers);

    /**
     * The paths with at most maxTransfers transfers. Rounds of the search never depend on later rounds, so that is
     * exactly the result of a search with maxTransfers.
     */
    SearchResult withMaxTransfers(int maxTransfers);

    /** Stations with a path, the start station included. */
    int stationCount();
}
