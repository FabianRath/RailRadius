package com.railreach.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Limits how many searches run at once. A full-data search holds about 330 MB while it runs, so without a limit enough
 * visitors at once run out of heap on any server size. Searches beyond the permits wait in a fair queue; when that is
 * full too, the next one is turned away as busy. Every search, the warmer's included, runs inside a permit.
 */
@Component
@Slf4j
public class SearchGate {

    // How often a queued search checks whether its client still waits for it
    private static final long QUEUE_CHECK_MS = 1000;

    private final Semaphore permits;
    private final int maxQueued;
    private final AtomicInteger queued = new AtomicInteger();

    public SearchGate(@Value("${railreach.search.gate.permits:2}") int permits,
                      @Value("${railreach.search.gate.max-queued:6}") int maxQueued) {
        this.permits = new Semaphore(permits, true);
        this.maxQueued = maxQueued;
    }

    /** Thrown when every permit is taken and the queue is full. */
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public static class BusyException extends RuntimeException {
        BusyException() {
            super("Too many searches at once, please try again in a minute");
        }
    }

    /**
     * Runs the search once a permit is free. While it waits in the queue, whileQueued runs about every second; it
     * may throw to leave the queue, e.g. when the client is gone.
     *
     * @throws BusyException if the queue is full
     */
    public <T> T run(Supplier<T> search, Runnable whileQueued) {
        if (!tryAcquireNow()) waitForPermit(whileQueued);
        try {
            return search.get();
        } finally {
            permits.release();
        }
    }

    /** Runs the search only if a permit is free and no search waits for one; empty otherwise. */
    public <T> Optional<T> runIfFree(Supplier<T> search) {
        if (!tryAcquireNow()) return Optional.empty();
        try {
            return Optional.of(search.get());
        } finally {
            permits.release();
        }
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
            log.warn("  Search turned away: every permit taken and {} searches queued", maxQueued);
            throw new BusyException();
        }
        long startMs = System.currentTimeMillis();
        try {
            while (!permits.tryAcquire(QUEUE_CHECK_MS, TimeUnit.MILLISECONDS)) {
                whileQueued.run();
            }
            log.info("  Search slot after {} ms in the queue", System.currentTimeMillis() - startMs);
        } catch (InterruptedException e) {
            throw interrupted(e);
        } finally {
            queued.decrementAndGet();
        }
    }

    private static CancellationException interrupted(InterruptedException e) {
        Thread.currentThread().interrupt();
        CancellationException cancelled = new CancellationException("Interrupted while waiting for a search slot");
        cancelled.initCause(e);
        return cancelled;
    }
}
