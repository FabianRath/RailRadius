package com.railradius.service;

import com.railradius.dto.ReachabilityRequest;
import com.railradius.dto.ReachableStation;
import com.railradius.dto.ReachableStationSummary;
import com.railradius.service.ReachabilityCache.SearchKey;
import com.railradius.service.SearchRequestValidator.ValidSearch;
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

/** Answers reachability requests: validates them, gets the (cached) search result and maps it to DTOs. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReachabilityService {

    private static final int STREAM_BATCH_SIZE = 30;
    private static final String OUT_OF_MEMORY_MESSAGE = "The server is too busy for this search, please try again later";
    // An unexpected exception's text is internal detail, so the client gets this instead (B43)
    private static final String INTERNAL_ERROR_MESSAGE = "Something went wrong on the server, please try again later";

    private final ReachabilityCache cache;
    private final ReachabilityWarmer warmer;
    private final JourneyLegBuilder legBuilder;
    private final SearchRateLimiter rateLimiter;
    private final SearchRequestValidator validator;

    @Value("${railradius.cache.predictive-enabled:true}")
    private boolean predictiveEnabled;

    /**
     * The journey to targetId with the path the stream sent for it; empty if the target is not reachable within the
     * request's limits. The search result is usually stored by the stream that showed the station.
     *
     * @throws SearchRateLimiter.TooManySearchesException if it would have to search and the client's budget is too low
     * @throws SearchRequestValidator.InvalidSearchException if the station is unknown or a parameter out of range
     */
    public Optional<ReachableStation> journey(SearchRateLimiter.Client client, ReachabilityRequest request,
                                              String targetId) {
        ValidSearch search = validator.validate(request);
        if (targetId.equals(search.key().startId())) return Optional.empty();
        chargeUnlessStored(client, search.key());
        CompactResult result = cache.getOrCompute(search.key());
        return fewestTransfersWithin(result.paths(targetId), search.maxJourneyMinutes())
                .flatMap(path -> legBuilder.toReachableStation(targetId, result.journey(targetId, path.transfers())));
    }

    /**
     * Resolves the request and sends the stations to the emitter in batches, then a "done" event. A search sends
     * each round's stations when the round ends; a stored result is sent at once. Runs on a worker thread; the HTTP
     * response stays open until it completes. A search that waits for a {@link SearchGate} permit sends SSE comments
     * meanwhile, and the search stops when a send fails because the client is gone, which frees its permit. A client
     * over its {@link SearchRateLimiter} budget gets an error event instead, and so does one whose search ran past its
     * deadline.
     */
    public void calculateReachabilityStreaming(SearchRateLimiter.Client client, ReachabilityRequest request,
                                               SseEmitter emitter) {
        long startMs = System.currentTimeMillis();
        try {
            ValidSearch search = validator.validate(request);
            chargeUnlessStored(client, search.key());
            StreamedStations streamed = new StreamedStations(search, emitter, startMs);
            // A station is sent once, with its first path within the time limit. Rounds add paths by ascending
            // transfers, so that is the path its journey shows too (B4).
            streamed.sendNew(cache.getOrCompute(search.key(), streamed));
            log.info("Streamed {} stations in {} ms, the first after {} ms", streamed.sent.size(),
                    System.currentTimeMillis() - startMs, streamed.firstSentMs);
            streamed.sendDone();
            emitter.complete();

            if (predictiveEnabled) {
                warmer.warmHigherTransferCounts(search.key());
            }
        } catch (CancellationException e) {
            log.info("Stream stopped after {} ms: {}", System.currentTimeMillis() - startMs, e.getMessage());
            emitter.complete();
        } catch (SearchRequestValidator.InvalidSearchException | SearchGate.BusyException
                 | SearchRateLimiter.TooManySearchesException | CompactReachabilitySearch.DeadlineExceededException e) {
            sendError(emitter, e.getMessage());
        } catch (Exception e) {
            log.error("Search from {} x{} failed", request.getStartStationId(), request.getMaxTransfers(), e);
            sendError(emitter, INTERNAL_ERROR_MESSAGE);
        } catch (OutOfMemoryError e) {
            // The failed search's memory is garbage once it has unwound, and its permit is free, so the app goes on;
            // without this the client would wait for an event forever (B38)
            log.error("Search from {} x{} ran out of memory", request.getStartStationId(), request.getMaxTransfers(),
                    e);
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

    // A stored result costs no search, so it costs no budget either. One that waits for an equal running search is
    // charged all the same: it searches itself if that one's client leaves, and a free wait would let a client that
    // starts a search and leaves hand out searches nobody paid for (S37)
    private void chargeUnlessStored(SearchRateLimiter.Client client, SearchKey key) {
        if (client.admin() || cache.contains(key)) return;
        rateLimiter.charge(client.ip(), key.maxTransfers(), key.maxTransferMins());
    }

    public Map<String, Object> getCacheStats() {
        List<String> warming = warmer.getWarmingInProgress();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("warmingInProgress", warming);
        stats.put("warmingCount", warming.size());
        stats.putAll(cache.getStats());
        return stats;
    }

    /** The stations a stream has sent so far. The start station is not a destination, so it is never sent. */
    private final class StreamedStations implements ReachabilityCache.SearchListener {

        private final String startId;
        private final SseEmitter emitter;
        private final long startMs;
        private final int maxJourneyMins;
        private final Set<String> sent = new HashSet<>();
        private long firstSentMs = -1;

        StreamedStations(ValidSearch search, SseEmitter emitter, long startMs) {
            this.startId = search.key().startId();
            this.emitter = emitter;
            this.startMs = startMs;
            this.maxJourneyMins = search.maxJourneyMinutes();
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

        void sendDone() {
            send(SseEmitter.event().name("done").data(Map.of(
                    "total", sent.size(),
                    "calculationTimeMs", System.currentTimeMillis() - startMs)));
        }

        private void addIfNew(List<ReachableStationSummary> stations, String stopId,
                              List<CompactResult.PathSummary> paths) {
            if (stopId.equals(startId) || sent.contains(stopId)) return;
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
