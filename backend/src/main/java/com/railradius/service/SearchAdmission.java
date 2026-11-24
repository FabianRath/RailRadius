package com.railradius.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Admits searches (GLOSSARY.md → Admission): a search is charged to its client unless its result is stored, joins an
 * equal search running now ({@link InFlightSearches}), else waits for a {@link SearchGate} permit and runs within the
 * deadline, and its result is stored. It ends in a result or a {@link SearchRefusal}. It owns every limit a search
 * waits or runs under, so the longest a client can wait for a result follows from them ({@link #longestWait}).
 */
@Component
@Slf4j
public class SearchAdmission {

    // How often a waiting search (queued, or waiting for an equal one) checks whether its client still waits for it
    private static final Duration WAIT_CHECK = Duration.ofSeconds(1);

    /** Who asks for a search; admins are never charged. */
    public record Client(String ip, boolean admin) {}

    /** What a caller hears of its search while it waits and runs. */
    public interface SearchListener {

        SearchListener NONE = new SearchListener() {};

        /**
         * Runs about every second while the search waits for a permit or for an equal search; may throw a
         * CancellationException to give up.
         */
        default void queued() {}

        /** Each round's new path per station, as the search goes. */
        default void roundPaths(Map<String, CompactResult.PathSummary> paths) {}
    }

    private final ReachabilityCache cache;
    private final CompactReachabilitySearch engine;
    private final SearchRateLimiter rateLimiter;
    private final SearchGate gate;
    private final InFlightSearches inFlight;
    private final Duration deadline;

    @Autowired
    public SearchAdmission(ReachabilityCache cache, CompactReachabilitySearch engine, SearchRateLimiter rateLimiter,
                           @Value("${railradius.search.gate.permits:2}") int permits,
                           @Value("${railradius.search.gate.max-queued:6}") int maxQueued,
                           @Value("${railradius.search.deadline-ms:30000}") long deadlineMs) {
        this(cache, engine, rateLimiter, new SearchGate(permits, maxQueued, WAIT_CHECK), Duration.ofMillis(deadlineMs),
                WAIT_CHECK);
    }

    SearchAdmission(ReachabilityCache cache, CompactReachabilitySearch engine, SearchRateLimiter rateLimiter,
                    SearchGate gate, Duration deadline, Duration waitCheck) {
        this.cache = cache;
        this.engine = engine;
        this.rateLimiter = rateLimiter;
        this.gate = gate;
        this.inFlight = new InFlightSearches(waitCheck);
        this.deadline = deadline;
    }

    /**
     * The search's result: the stored one, else that of the equal search running now, else searched and stored. All
     * but a stored result is charged first, joining included: a joiner searches itself if the search it waits for
     * stops because its client left, and a free wait would let a client that starts a search and leaves hand out
     * searches nobody paid for (S37). The charge follows the lookup that serves the result, so a result evicted
     * meanwhile is never searched for free (#16).
     *
     * @throws SearchRefusal if the client's budget is too low, the queue is full or the search runs past its deadline
     */
    public CompactResult admit(Client client, SearchOptions options, SearchListener listener) {
        Optional<CompactResult> stored = cache.get(options);
        if (stored.isPresent()) return stored.get();
        if (!client.admin()) rateLimiter.charge(client.ip(), options);
        return inFlight.joinOrRun(options, () -> gate.run(() -> storedOrSearched(options, listener), listener::queued),
                listener::queued);
    }

    /**
     * Searches and stores the result for the warmer, only if a permit is free right now and no equal search runs. It
     * is never charged and never waits, so it never delays a visitor's search. True if the result is stored.
     */
    public boolean warmIfIdle(SearchOptions options) {
        return inFlight.runUnlessRunning(options,
                () -> gate.runIfFree(() -> storedOrSearched(options, SearchListener.NONE))).isPresent();
    }

    /** The longest a search waits behind a full queue and then runs: how long a client may wait for a result. */
    public Duration longestWait() {
        return gate.longestWait(deadline);
    }

    // Checked again with the permit: an equal search may have finished while this one waited
    private CompactResult storedOrSearched(SearchOptions options, SearchListener listener) {
        return cache.get(options).orElseGet(() -> {
            log.info("  Search: {} x{}", options.startId(), options.maxTransfers());
            CompactResult result = engine.search(options.startId(), options.maxTransfers(), options.minTransferMins(),
                    options.maxTransferMins(), deadline, listener::roundPaths);
            cache.put(options, result);
            return result;
        });
    }
}
