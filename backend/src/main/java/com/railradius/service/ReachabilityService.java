package com.railradius.service;

import com.railradius.dto.ReachableStation;
import com.railradius.dto.ReachableStationSummary;
import com.railradius.service.SearchRequestValidator.SearchRequest;
import com.railradius.service.SearchRequestValidator.ValidSearch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Answers reachability requests: validates them, has the search admitted ({@link SearchAdmission}), maps results. */
@Service
@Slf4j
public class ReachabilityService {

    private static final int STREAM_BATCH_SIZE = 30;
    private static final String OUT_OF_MEMORY_MESSAGE = "The server is too busy for this search, please try again later";
    // An unexpected exception's text is internal detail, so the client gets this instead (B43)
    private static final String INTERNAL_ERROR_MESSAGE = "Something went wrong on the server, please try again later";

    private final ReachabilityCache cache;
    private final SearchAdmission admission;
    private final ReachabilityWarmer warmer;
    private final JourneyLegBuilder legBuilder;
    private final TrackLines trackLines;
    private final SearchRequestValidator validator;
    private final TransitGraph graph;
    private final SearchCounts searchCounts;
    private final Executor roundSender;

    @Value("${railradius.cache.predictive-enabled:true}")
    private boolean predictiveEnabled;

    // With @EnableScheduling the task scheduler is an Executor too; rounds are sent on the task executor
    public ReachabilityService(ReachabilityCache cache, SearchAdmission admission, ReachabilityWarmer warmer,
                               JourneyLegBuilder legBuilder, TrackLines trackLines, SearchRequestValidator validator,
                               TransitGraph graph, SearchCounts searchCounts,
                               @Qualifier("applicationTaskExecutor") Executor roundSender) {
        this.cache = cache;
        this.admission = admission;
        this.warmer = warmer;
        this.legBuilder = legBuilder;
        this.trackLines = trackLines;
        this.validator = validator;
        this.graph = graph;
        this.searchCounts = searchCounts;
        this.roundSender = roundSender;
    }

    /**
     * The journey to targetId with the path the stream sent for it; empty if the target is not reachable within the
     * request's limits. The search result is usually stored by the stream that showed the station.
     *
     * @throws SearchRefusal if the request is invalid or the search is refused ({@link SearchAdmission#admit})
     */
    public Optional<ReachableStation> journey(SearchAdmission.Client client, SearchRequest request, String targetId) {
        ValidSearch search = validator.validate(request);
        if (targetId.equals(search.options().startId())) return Optional.empty();
        CompactResult result = admission.admit(client, search.options(), SearchAdmission.SearchListener.NONE);
        return ShownStations.fewestTransfersWithin(result.paths(targetId), search.maxJourneyMinutes())
                .flatMap(path -> legBuilder.toReachableStation(result, targetId, path.transfers()))
                .map(station -> trackLines.addTo(station, result.served()::station));
    }

    /**
     * Resolves the request and sends the stations to the emitter in batches, then a "done" event. A search sends
     * each round's stations when the round ends, on another thread while the next round runs; a stored result is sent
     * at once. Runs on a worker thread; the HTTP
     * response stays open until it completes. A search that waits for a {@link SearchGate} permit sends SSE comments
     * meanwhile, and the search stops when a send fails because the client is gone, which frees its permit. A refused
     * search ({@link SearchRefusal}) gets an error event with the refusal's message instead.
     */
    public void calculateReachabilityStreaming(SearchAdmission.Client client, SearchRequest request,
                                               SseEmitter emitter) {
        long startMs = System.currentTimeMillis();
        try {
            ValidSearch search = validator.validate(request);
            searchCounts.countServerSearch();
            StreamedStations streamed = new StreamedStations(search, emitter, startMs);
            // A station is sent once, with its first path within the time limit. Rounds add paths by ascending
            // transfers, so that is the path its journey shows too (B4).
            streamed.sendNew(streamed.search(client));
            log.info("Streamed {} stations in {} ms, the first after {} ms", streamed.shown.count(),
                    System.currentTimeMillis() - startMs, streamed.firstSentMs);
            streamed.sendDone();
            emitter.complete();

            if (predictiveEnabled) {
                warmer.warmHigherTransferCounts(search.options());
            }
        } catch (CancellationException e) {
            log.info("Stream stopped after {} ms: {}", System.currentTimeMillis() - startMs, e.getMessage());
            emitter.complete();
        } catch (SearchRefusal e) {
            sendError(emitter, e.getReason());
        } catch (Exception e) {
            log.error("Search from {} x{} failed", request.startId(), request.maxTransfers(), e);
            sendError(emitter, INTERNAL_ERROR_MESSAGE);
        } catch (OutOfMemoryError e) {
            // The failed search's memory is garbage once it has unwound, and its permit is free, so the app goes on;
            // without this the client would wait for an event forever (B38)
            log.error("Search from {} x{} ran out of memory", request.startId(), request.maxTransfers(),
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

    public Map<String, Object> getCacheStats() {
        List<String> warming = warmer.getWarmingInProgress();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("warmingInProgress", warming);
        stats.put("warmingCount", warming.size());
        stats.putAll(cache.getStats());
        stats.put("searchesPerDay", searchCounts.perDay());
        return stats;
    }

    /** Sends the stations a search shows ({@link ShownStations}) as they come. */
    private final class StreamedStations implements SearchAdmission.SearchListener {

        private final SearchOptions options;
        private final ShownStations shown;
        private final SseEmitter emitter;
        private final long startMs;
        private long firstSentMs = -1;
        // The round being built and sent on roundSender, so the search goes on meanwhile (#84)
        private CompletableFuture<Void> sending = CompletableFuture.completedFuture(null);

        StreamedStations(ValidSearch search, SseEmitter emitter, long startMs) {
            this.options = search.options();
            this.shown = new ShownStations(search.options().startId(), search.maxJourneyMinutes(), legBuilder);
            this.emitter = emitter;
            this.startMs = startMs;
        }

        @Override
        public void queued() {
            send(SseEmitter.event().comment("queued"));
        }

        // A round still being sent when the search fails is let finish first, so the error event comes last
        CompactResult search(SearchAdmission.Client client) {
            try {
                return admission.admit(client, options, this);
            } finally {
                sending.exceptionally(sendFailure -> null).join();
            }
        }

        @Override
        public void roundPaths(Map<String, CompactResult.PathSummary> paths) {
            // No result yet to name the graph: a station missing from the one served now is sent with the result
            ServedGraph served = graph.served();
            // One round at a time keeps the rounds in order, and a gone client stops the search a round later
            awaitSending();
            sending = CompletableFuture.runAsync(() -> sendBatches(shown.newInRound(served, paths)), roundSender);
        }

        /**
         * Sends the stations not sent yet, as summaries: a station's journey is fetched when it is clicked
         * ({@link #journey}).
         */
        void sendNew(CompactResult result) {
            awaitSending();
            sendBatches(shown.newInResult(result));
        }

        void sendDone() {
            send(SseEmitter.event().name("done").data(Map.of(
                    "total", shown.count(),
                    "calculationTimeMs", System.currentTimeMillis() - startMs)));
        }

        private void sendBatches(List<ReachableStationSummary> stations) {
            if (!stations.isEmpty() && firstSentMs < 0) firstSentMs = System.currentTimeMillis() - startMs;
            for (int i = 0; i < stations.size(); i += STREAM_BATCH_SIZE) {
                send(SseEmitter.event().name("stations")
                        .data(stations.subList(i, Math.min(i + STREAM_BATCH_SIZE, stations.size()))));
            }
        }

        // Throws what failed the last round's send, as if the search thread had sent it
        private void awaitSending() {
            try {
                sending.join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException failure) throw failure;
                if (e.getCause() instanceof Error failure) throw failure;
                throw e;
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
}
