package com.railradius.service;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The searches running now, per key, so that an equal search waits for the running one instead of running again
 * (S37): a shared link opened by many at once costs one search. A waiting caller holds no {@link SearchGate} permit.
 */
@Slf4j
final class InFlightSearches {

    // How often a caller waiting for an equal search checks whether its own client still waits
    private final long waitCheckMs;
    private final ConcurrentHashMap<SearchOptions, CompletableFuture<CompactResult>> running =
            new ConcurrentHashMap<>();

    InFlightSearches(Duration waitCheck) {
        this.waitCheckMs = waitCheck.toMillis();
    }

    /**
     * The result of the equal search running now, else of search, which then runs as the key's only one. While it
     * waits for another caller's search, whileWaiting runs at each wait check and may throw to stop waiting. Gets the
     * other search's failure too, except when that one stopped because its own client is gone: then it searches itself.
     */
    CompactResult joinOrRun(SearchOptions key, Supplier<CompactResult> search, Runnable whileWaiting) {
        while (true) {
            CompletableFuture<CompactResult> mine = new CompletableFuture<>();
            CompletableFuture<CompactResult> other = running.putIfAbsent(key, mine);
            if (other == null) return lead(key, mine, () -> Optional.of(search.get())).orElseThrow();
            log.info("  Search: {} x{} waits for an equal search", key.startId(), key.maxTransfers());
            Optional<CompactResult> result = await(key, other, whileWaiting);
            if (result.isPresent()) return result.get();
        }
    }

    /** Runs search unless an equal one runs now; empty then, or if search returns empty. Never waits. */
    Optional<CompactResult> runUnlessRunning(SearchOptions key, Supplier<Optional<CompactResult>> search) {
        CompletableFuture<CompactResult> mine = new CompletableFuture<>();
        if (running.putIfAbsent(key, mine) != null) return Optional.empty();
        return lead(key, mine, search);
    }

    // A search that stopped without a result for others (its client is gone, or nothing was searched) is cancelled,
    // so its waiters search themselves
    private Optional<CompactResult> lead(SearchOptions key, CompletableFuture<CompactResult> mine,
                                         Supplier<Optional<CompactResult>> search) {
        try {
            Optional<CompactResult> result = search.get();
            result.ifPresentOrElse(mine::complete, () -> mine.cancel(false));
            return result;
        } catch (CancellationException e) {
            mine.cancel(false);
            throw e;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            running.remove(key, mine);
        }
    }

    // Empty if the other search was cancelled; it is dropped here too, so the next try need not wait for its finally
    private Optional<CompactResult> await(SearchOptions key, CompletableFuture<CompactResult> other,
                                          Runnable whileWaiting) {
        while (true) {
            try {
                return Optional.of(other.get(waitCheckMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                whileWaiting.run();
            } catch (CancellationException e) {
                running.remove(key, other);
                return Optional.empty();
            } catch (ExecutionException e) {
                throw rethrown(e.getCause());
            } catch (InterruptedException e) {
                throw SearchGate.interrupted(e);
            }
        }
    }

    // The search's supplier throws nothing checked, so the cause is unchecked
    private static RuntimeException rethrown(Throwable cause) {
        if (cause instanceof Error error) throw error;
        if (cause instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException(cause);
    }
}
