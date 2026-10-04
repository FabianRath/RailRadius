package com.railreach.service;

import com.railreach.dto.ReachabilityRequest;
import com.railreach.dto.ReachabilityResponse;
import com.railreach.dto.ReachableStation;
import com.railreach.model.JourneyPath;
import com.railreach.model.Station;
import com.railreach.service.ReachabilityCache.SearchKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Answers reachability requests: resolves defaults, gets the (cached) search result and maps it to DTOs. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReachabilityService {

    private static final int STREAM_BATCH_SIZE = 30;
    private static final int DEFAULT_MAX_TRANSFERS = 5;
    private static final int MINUTES_PER_HOUR = 60;

    private final TransitGraph transitGraph;
    private final ReachabilityCache cache;
    private final ReachabilityWarmer warmer;
    private final JourneyLegBuilder legBuilder;

    @Value("${railreach.algorithm.max-transfer-time-minutes:180}")
    private int maxTransferTimeMinutes;

    @Value("${railreach.cache.predictive-enabled:true}")
    private boolean predictiveEnabled;

    @Cacheable(value = ReachabilityCache.RESPONSE_CACHE_NAME, key = "#request.startStationId + '-' + #request.maxTransfers + '-' + #request.minTransferTimeMinutes + '-' + #request.maxTransferTimeMinutes")
    public ReachabilityResponse calculateReachability(ReachabilityRequest request) {
        long startMs = System.currentTimeMillis();
        SearchKey key = searchKey(request);
        log.info("Calculating reachability from {} with maxTransfers={}, transfer window={}–{}m",
                key.startId(), key.maxTransfers(), key.minTransferMins(), key.maxTransferMins());

        int maxJourneyMins = maxJourneyMinutes(request);
        List<ReachableStation> stations = new ArrayList<>();
        cache.getOrCompute(key).forEach((stopId, path) -> {
            if (path.getTotalDurationMinutes() > maxJourneyMins) return;
            legBuilder.toReachableStation(stopId, path).ifPresent(stations::add);
        });

        Station startStation = transitGraph.getStation(key.startId());
        long durationMs = System.currentTimeMillis() - startMs;
        log.info("Found {} reachable stations in {}ms", stations.size(), durationMs);
        return ReachabilityResponse.builder()
                .startStationId(key.startId())
                .startStationName(startStation != null ? startStation.getName() : "Unknown")
                .maxTransfers(key.maxTransfers())
                .totalReachableStations(stations.size())
                .calculationTimeMs(durationMs)
                .reachableStations(stations)
                .build();
    }

    /**
     * Resolves the request and sends the result to the emitter in batches, then a "done" event.
     * Runs on a worker thread; the HTTP response stays open until it completes.
     */
    public void calculateReachabilityStreaming(ReachabilityRequest request, SseEmitter emitter) {
        long startMs = System.currentTimeMillis();
        SearchKey key = searchKey(request);
        try {
            // Stream only the finished result: the BFS improves paths as it goes, so emitting
            // during the search would send stations more than once and with non-final paths.
            List<ReachableStation> stations = toStreamedStations(request, cache.getOrCompute(key));
            for (int i = 0; i < stations.size(); i += STREAM_BATCH_SIZE) {
                flushBatch(emitter, stations.subList(i, Math.min(i + STREAM_BATCH_SIZE, stations.size())));
            }
            // The raw cache now holds the authoritative result; a Caffeine entry may be older
            cache.evictResponse(key);

            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of(
                        "total", stations.size(),
                        "calculationTimeMs", System.currentTimeMillis() - startMs
                    )));
            emitter.complete();

            if (predictiveEnabled) {
                warmer.warmHigherTransferCounts(key);
            }
        } catch (Exception e) {
            log.error("Streaming BFS error", e);
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (Exception sendError) {
                log.debug("Could not send the error event, the client is gone", sendError);
            }
            emitter.completeWithError(e);
        }
    }

    public Map<String, Object> getCacheStats() {
        List<String> warming = warmer.getWarmingInProgress();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("warmingInProgress", warming);
        stats.put("warmingCount", warming.size());
        stats.putAll(cache.getStats());
        return stats;
    }

    private SearchKey searchKey(ReachabilityRequest request) {
        return new SearchKey(request.getStartStationId(),
                request.getMaxTransfers() != null ? request.getMaxTransfers() : DEFAULT_MAX_TRANSFERS,
                request.getMinTransferTimeMinutes() != null ? request.getMinTransferTimeMinutes() : 0,
                request.getMaxTransferTimeMinutes() != null ? request.getMaxTransferTimeMinutes() : maxTransferTimeMinutes);
    }

    /** No limit when the request has no maximum journey time. */
    private static int maxJourneyMinutes(ReachabilityRequest request) {
        Double hours = request.getMaxJourneyTimeHours();
        return hours != null ? (int) (hours * MINUTES_PER_HOUR) : Integer.MAX_VALUE;
    }

    /** The start station itself is not a destination, so it is never streamed. */
    private List<ReachableStation> toStreamedStations(ReachabilityRequest request, Map<String, JourneyPath> reachable) {
        int maxJourneyMins = maxJourneyMinutes(request);
        List<ReachableStation> stations = new ArrayList<>();
        reachable.forEach((stopId, path) -> {
            if (stopId.equals(request.getStartStationId()) || path.getTotalDurationMinutes() > maxJourneyMins) return;
            legBuilder.toReachableStation(stopId, path).ifPresent(stations::add);
        });
        return stations;
    }

    private void flushBatch(SseEmitter emitter, List<ReachableStation> stations) {
        try {
            emitter.send(SseEmitter.event().name("stations").data(stations));
        } catch (Exception e) {
            log.warn("SSE flush failed", e);
        }
    }
}
