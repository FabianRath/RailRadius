package com.railreach.service;

import com.railreach.model.JourneyPath;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Finds every station reachable from a start with at most a number of transfers. Two engines give the same result:
 * {@link ReachabilitySearch} and {@link CompactReachabilitySearch}, which searches the flat {@link TimetableStore}.
 * They run side by side, chosen by {@code railreach.search.engine}, until the compact one replaces the other (S21).
 */
public interface SearchEngine {

    String LEGACY = "legacy";
    String COMPACT = "compact";

    /**
     * Per station: the fastest path for each transfer count that is faster than every path with fewer transfers,
     * ordered by ascending transfers. So the first path has the fewest transfers and the last one is the fastest.
     * The start station maps to an empty path. After each round, roundPaths gets the paths that round added, per
     * station (never the start station).
     */
    Map<String, List<JourneyPath>> search(String startId, int maxTransfers, int minTransferMins, int maxTransferMins,
                                          Consumer<Map<String, List<JourneyPath>>> roundPaths);

    /** Builds what the engine searches on after a load, so the first search is as fast as later ones. */
    void prepare();

    /** The engine called {@link #LEGACY} or {@link #COMPACT}. */
    static SearchEngine named(String engine, TransitGraph graph) {
        return switch (engine) {
            case LEGACY -> new ReachabilitySearch(graph);
            case COMPACT -> new CompactReachabilitySearch(graph);
            default -> throw new IllegalArgumentException(
                    "Unknown search engine '" + engine + "': use " + LEGACY + " or " + COMPACT);
        };
    }
}
