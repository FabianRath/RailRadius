package com.railradius.service;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Limits how many searches run at once. A full-data search holds about 330 MB while it runs, so without a limit enough
 * visitors at once run out of heap on any server size. Searches beyond the permits wait in a fair queue; when that is
 * full too, the next one is refused as busy. Every search, the warmer's included, runs inside a permit. Built by
 * {@link SearchAdmission}.
 */
@Slf4j
final class SearchGate {

    private final int permitCount;
    private final Semaphore permits;
    private final int maxQueued;
    // How often a queued search checks whether its client still waits for it
    private final long waitCheckMs;
    private final AtomicInteger queued = new AtomicInteger();

    SearchGate(int permits, int maxQueued, Duration waitCheck) {
        this.permitCount = permits;
        this.permits = new Semaphore(permits, true);
        this.maxQueued = maxQueued;
        this.waitCheckMs = waitCheck.toMillis();
    }

    /**
     * Runs the search once a permit is free. While it waits in the queue, whileQueued runs at each wait check; it
     * may throw to leave the queue, e.g. when the client is gone.
     *
     * @throws SearchRefusal if the queue is full
     */
    <T> T run(Supplier<T> search, Runnable whileQueued) {
        if (!tryAcquireNow()) waitForPermit(whileQueued);
        try {
            return search.get();
        } finally {
            permits.release();
        }
    }

    /** Runs the search only if a permit is free and no search waits for one; empty otherwise. */
    <T> Optional<T> runIfFree(Supplier<T> search) {
        if (!tryAcquireNow()) return Optional.empty();
        try {
            return Optional.of(search.get());
        } finally {
            permits.release();
        }
    }

    /**
     * The longest a search waits in the queue and then runs, if each search runs at most perSearch: the last one in a
     * full queue waits for a wave of searches per permits-full of the queue ahead of and including it, then runs.
     */
    Duration longestWait(Duration perSearch) {
        int queueWaves = (maxQueued + permitCount - 1) / permitCount;
        return perSearch.multipliedBy(queueWaves + 1L);
    }

    // A timed tryAcquire keeps the fair order; the untimed one would jump the queue
    private boolean tryAcquireNow() {
        try {
            return permits.tryAcquire(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw interrupted(e);
        }
    }

    private void waitForPermit(Runnable whileQueued) {
        if (queued.incrementAndGet() > maxQueued) {
            queued.decrementAndGet();
            log.warn("  Search refused: every permit taken and {} searches queued", maxQueued);
            throw SearchRefusal.busy();
        }
        long startMs = System.currentTimeMillis();
        try {
            while (!permits.tryAcquire(waitCheckMs, TimeUnit.MILLISECONDS)) {
                whileQueued.run();
            }
            log.info("  Search slot after {} ms in the queue", System.currentTimeMillis() - startMs);
        } catch (InterruptedException e) {
            throw interrupted(e);
        } finally {
            queued.decrementAndGet();
        }
    }

    static CancellationException interrupted(InterruptedException e) {
        Thread.currentThread().interrupt();
        CancellationException cancelled = new CancellationException("Interrupted while waiting for a search");
        cancelled.initCause(e);
        return cancelled;
    }
}
