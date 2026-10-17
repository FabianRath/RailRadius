package com.railradius.service;

import com.railradius.dto.ReachabilityRequest;
import com.railradius.service.ReachabilityCache.SearchKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Checks a reachability request against the bounds the API allows and fills in its defaults (B41, B42). A search key
 * comes only from here, so the result cache and the rate limit never see a value the client made up: an unknown
 * station or an out-of-range parameter is turned away before anything is charged, searched or stored.
 */
@Component
public class SearchRequestValidator {

    public static final int MAX_TRANSFERS = 5;
    // The longest wait at a change: longer windows make a search many times slower than the rate limit charges for
    public static final int MAX_TRANSFER_WAIT_MINUTES = 360;
    private static final double MIN_JOURNEY_HOURS = 0.5;
    private static final double MAX_JOURNEY_HOURS = 24;
    private static final int MINUTES_PER_HOUR = 60;

    /** A request that passed the checks: what to search, and the longest journey to show. */
    public record ValidSearch(SearchKey key, int maxJourneyMinutes) {}

    /** Thrown for a request outside the API's bounds; the message is safe to show to the client. */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidSearchException extends IllegalArgumentException {
        InvalidSearchException(String message) {
            super(message);
        }
    }

    private final TransitGraph graph;
    private final int defaultMaxTransferMinutes;

    public SearchRequestValidator(TransitGraph graph,
                                  @Value("${railradius.algorithm.max-transfer-time-minutes:180}")
                                  int defaultMaxTransferMinutes) {
        this.graph = graph;
        this.defaultMaxTransferMinutes = defaultMaxTransferMinutes;
    }

    /** @throws InvalidSearchException if the station is unknown or a parameter is out of range */
    public ValidSearch validate(ReachabilityRequest request) {
        String startId = request.getStartStationId();
        if (startId == null || graph.getStation(startId) == null) throw new InvalidSearchException("Unknown station");
        int maxTransfers = orDefault(request.getMaxTransfers(), MAX_TRANSFERS);
        int minTransferMins = orDefault(request.getMinTransferTimeMinutes(), 0);
        int maxTransferMins = orDefault(request.getMaxTransferTimeMinutes(), defaultMaxTransferMinutes);
        if (maxTransfers < 0 || maxTransfers > MAX_TRANSFERS) {
            throw new InvalidSearchException("Changes must be between 0 and " + MAX_TRANSFERS);
        }
        if (minTransferMins < 0 || minTransferMins > maxTransferMins || maxTransferMins > MAX_TRANSFER_WAIT_MINUTES) {
            throw new InvalidSearchException("The wait at changes must be between 0 and " + MAX_TRANSFER_WAIT_MINUTES
                    + " minutes, the minimum not above the maximum");
        }
        return new ValidSearch(new SearchKey(startId, maxTransfers, minTransferMins, maxTransferMins),
                maxJourneyMinutes(request.getMaxJourneyTimeHours()));
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

    private static int orDefault(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }
}
