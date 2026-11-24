package com.railradius.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns a reachability request into the search to run: the start station must exist, the options must be within
 * their bounds ({@link SearchOptions}) and the journey time limit within its own. A request that fails is turned away
 * before anything is charged, searched or stored.
 */
@Component
@RequiredArgsConstructor
public class SearchRequestValidator {

    private static final double MIN_JOURNEY_HOURS = 0.5;
    private static final double MAX_JOURNEY_HOURS = 24;
    private static final int MINUTES_PER_HOUR = 60;

    /** A request as the client sent it; a null wait or journey limit means the client left it out. */
    public record SearchRequest(String startId, int maxTransfers, Integer minTransferMins, Integer maxTransferMins,
                                Double maxJourneyHours) {}

    /** A request that passed the checks: what to search, and the longest journey to show. */
    public record ValidSearch(SearchOptions options, int maxJourneyMinutes) {}

    private final TransitGraph graph;

    /** @throws InvalidSearchException if the station is unknown or a parameter is out of range */
    public ValidSearch validate(SearchRequest request) {
        String startId = request.startId();
        if (startId == null || graph.getStation(startId) == null) throw new InvalidSearchException("Unknown station");
        SearchOptions options = SearchOptions.of(startId, request.maxTransfers(), request.minTransferMins(),
                request.maxTransferMins());
        return new ValidSearch(options, maxJourneyMinutes(request.maxJourneyHours()));
    }

    // No limit when the request has none. NaN fails both comparisons, so it is rejected too
    private static int maxJourneyMinutes(Double hours) {
        if (hours == null) return Integer.MAX_VALUE;
        if (!(hours >= MIN_JOURNEY_HOURS && hours <= MAX_JOURNEY_HOURS)) {
            throw new InvalidSearchException("The max journey time must be between " + MIN_JOURNEY_HOURS + " and "
                    + (int) MAX_JOURNEY_HOURS + " hours");
        }
        return (int) (hours * MINUTES_PER_HOUR);
    }
}
