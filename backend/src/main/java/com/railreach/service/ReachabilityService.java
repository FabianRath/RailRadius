package com.railreach.service;

import com.railreach.dto.ReachabilityRequest;
import com.railreach.dto.ReachableStation;
import com.railreach.dto.ReachableStationSummary;
import com.railreach.service.ReachabilityCache.SearchKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Answers reachability requests: resolves defaults, gets the (cached) search result and maps it to DTOs. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReachabilityService {

    private static final int STREAM_BATCH_SIZE = 30;
    private static final int DEFAULT_MAX_TRANSFERS = 5;
    private static final int MINUTES_PER_HOUR = 60;
    private static final String OUT_OF_MEMORY_MESSAGE = "The server is too busy for this search, please try again later";

    private final ReachabilityCache cache;
    private final ReachabilityWarmer warmer;
    private final JourneyLegBuilder legBuilder;

    @Value("${railreach.algorithm.max-transfer-time-minutes:180}")
    private int maxTransferTimeMinutes;

    @Value("${railreach.cache.predictive-enabled:true}")
    private boolean predictiveEnabled;

    /**
     * The journey to targetId with the path the stream sent for it; empty if the target is not reachable within the
     * request's limits. The search result is usually stored by the stream that showed the station.
     */
    public Optional<ReachableStation> journey(ReachabilityRequest request, String targetId) {
        if (targetId.equals(request.getStartStationId())) return Optional.empty();
        CompactResult result = cache.getOrCompute(searchKey(request));
        return fewestTransfersWithin(result.paths(targetId), maxJourneyMinutes(request))
                .flatMap(path -> legBuilder.toReachableStation(targetId, result.journey(targetId, path.transfers())));
    }

    /**
     * Resolves the request and sends the stations to the emitter in batches, then a "done" event. A search sends
     * each round's stations when the round ends; a stored result is sent at once. Runs on a worker thread; the HTTP
     * response stays open until it completes. A search that waits for a {@link SearchGate} permit sends SSE comments
     * meanwhile, and the search stops when a send fails because the client is gone, which frees its permit.
     */
    public void calculateReachabilityStreaming(ReachabilityRequest request, SseEmitter emitter) {
        long startMs = System.currentTimeMillis();
        SearchKey key = searchKey(request);
        try {
            StreamedStations streamed = new StreamedStations(request, emitter, startMs);
            // A station is sent once, with its first path within the time limit. Rounds add paths by ascending
            // transfers, so that is the path its journey shows too (B4).
            streamed.sendNew(cache.getOrCompute(key, streamed));
            log.info("Streamed {} stations in {} ms, the first after {} ms", streamed.sent.size(),
                    System.currentTimeMillis() - startMs, streamed.firstSentMs);
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of(
                        "total", streamed.sent.size(),
                        "calculationTimeMs", System.currentTimeMillis() - startMs
                    )));
            emitter.complete();

            if (predictiveEnabled) {
                warmer.warmHigherTransferCounts(key);
            }
        } catch (CancellationException e) {
            log.info("Stream stopped after {} ms: {}", System.currentTimeMillis() - startMs, e.getMessage());
            emitter.complete();
        } catch (SearchGate.BusyException e) {
            sendError(emitter, e.getMessage());
        } catch (Exception e) {
            log.error("Streaming BFS error", e);
            sendError(emitter, e.getMessage());
        } catch (OutOfMemoryError e) {
            // The failed search's memory is garbage once it has unwound, and its permit is free, so the app goes on;
            // without this the client would wait for an event forever (B38)
            log.error("Search from {} x{} ran out of memory", key.startId(), key.maxTransfers(), e);
            sendError(emitter, OUT_OF_MEMORY_MESSAGE);
        }
    }

    // Ends the stream normally: the error event is the client's answer and the caller has logged what it needs.
    // completeWithError would hand the exception to Spring after the response is committed, which logs it as a
    // server error (B39)
    private static void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
        } catch (Exception sendError) {
            log.debug("Could not send the error event, the client is gone", sendError);
        }
        emitter.complete();
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

    /** The stations a stream has sent so far. The start station is not a destination, so it is never sent. */
    private final class StreamedStations implements ReachabilityCache.SearchListener {

        private final ReachabilityRequest request;
        private final SseEmitter emitter;
        private final long startMs;
        private final int maxJourneyMins;
        private final Set<String> sent = new HashSet<>();
        private long firstSentMs = -1;

        StreamedStations(ReachabilityRequest request, SseEmitter emitter, long startMs) {
            this.request = request;
            this.emitter = emitter;
            this.startMs = startMs;
            this.maxJourneyMins = maxJourneyMinutes(request);
        }

        @Override
        public void queued() {
            send(SseEmitter.event().comment("queued"));
        }

        @Override
        public void roundPaths(Map<String, CompactResult.PathSummary> paths) {
            List<ReachableStationSummary> stations = new ArrayList<>();
            paths.forEach((stopId, path) -> addIfNew(stations, stopId, List.of(path)));
            sendBatches(stations);
        }

        /**
         * Sends the stations not sent yet that have a path within the time limit, as summaries: a station's journey
         * is fetched when it is clicked ({@link #journey}).
         */
        void sendNew(CompactResult result) {
            List<ReachableStationSummary> stations = new ArrayList<>();
            result.forEachStation((stopId, paths) -> addIfNew(stations, stopId, paths));
            sendBatches(stations);
        }

        private void addIfNew(List<ReachableStationSummary> stations, String stopId,
                              List<CompactResult.PathSummary> paths) {
            if (stopId.equals(request.getStartStationId()) || sent.contains(stopId)) return;
            fewestTransfersWithin(paths, maxJourneyMins)
                    .flatMap(path -> legBuilder.toSummary(stopId, path))
                    .ifPresent(station -> {
                        stations.add(station);
                        sent.add(stopId);
                    });
        }

        private void sendBatches(List<ReachableStationSummary> stations) {
            if (!stations.isEmpty() && firstSentMs < 0) firstSentMs = System.currentTimeMillis() - startMs;
            for (int i = 0; i < stations.size(); i += STREAM_BATCH_SIZE) {
                send(SseEmitter.event().name("stations")
                        .data(stations.subList(i, Math.min(i + STREAM_BATCH_SIZE, stations.size()))));
            }
        }

        // A failed send means the client is gone: stop instead of searching for nobody
        private void send(SseEmitter.SseEventBuilder event) {
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException e) {
                CancellationException gone = new CancellationException("the client is gone");
                gone.initCause(e);
                throw gone;
            }
        }
    }

    // The paths are ordered by ascending transfers, so a station whose fewest-transfer path is too long still shows
    // up with a faster path that has more transfers (B4)
    private static Optional<CompactResult.PathSummary> fewestTransfersWithin(List<CompactResult.PathSummary> paths,
                                                                             int maxJourneyMins) {
        return paths.stream().filter(path -> path.durationMinutes() <= maxJourneyMins).findFirst();
    }
}
