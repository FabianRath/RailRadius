package com.railreach.service;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Takes one permit of a {@link SearchGate} on another thread, as a running search would, and frees it on close. */
final class HeldPermit implements AutoCloseable {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final CountDownLatch release = new CountDownLatch(1);
    private final Thread holder;

    HeldPermit(SearchGate gate) throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        holder = Thread.ofVirtual().start(() -> gate.run(() -> {
            held.countDown();
            awaitRelease();
            return null;
        }, () -> {}));
        assertThat(held.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).as("permit taken").isTrue();
    }

    private void awaitRelease() {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws InterruptedException {
        release.countDown();
        assertThat(holder.join(TIMEOUT)).as("permit freed").isTrue();
    }
}
