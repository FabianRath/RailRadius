package com.railradius.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.railradius.service.HeldPermit.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchGateTest {

    @Test
    void aSearchBeyondThePermitsWaitsUntilOneIsFree() throws Exception {
        SearchGate gate = GtfsTestFixtures.gate(1, 1);
        CountDownLatch queued = new CountDownLatch(1);
        FutureTask<String> waiting;
        try (HeldPermit ignored = new HeldPermit(gate)) {
            waiting = startRun(gate, queued);
            assertThat(queued.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(waiting.isDone()).isFalse();
        }

        assertThat(waiting.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo("searched");
    }

    @Test
    void aSearchIsTurnedAwayWhenTheQueueIsFull() throws Exception {
        SearchGate gate = GtfsTestFixtures.gate(1, 1);
        CountDownLatch queued = new CountDownLatch(1);
        AtomicBoolean searched = new AtomicBoolean();
        try (HeldPermit ignored = new HeldPermit(gate)) {
            startRun(gate, queued);
            assertThat(queued.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

            assertThatThrownBy(() -> gate.run(() -> searched.getAndSet(true), () -> {}))
                    .isInstanceOf(SearchRefusal.class);
        }
        assertThat(searched).isFalse();
    }

    @Test
    void aQueuedSearchThatGivesUpLeavesTheQueue() throws Exception {
        SearchGate gate = GtfsTestFixtures.gate(1, 1);
        CountDownLatch queued = new CountDownLatch(1);
        FutureTask<String> waiting;
        try (HeldPermit ignored = new HeldPermit(gate)) {
            assertThatThrownBy(() -> gate.run(() -> "searched", () -> {
                throw new CancellationException("the client is gone");
            })).isInstanceOf(CancellationException.class);

            waiting = startRun(gate, queued);
            assertThat(queued.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }

        assertThat(waiting.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo("searched");
    }

    @Test
    void runIfFreeSkipsTheSearchWhileEveryPermitIsTaken() throws Exception {
        SearchGate gate = GtfsTestFixtures.gate(1, 1);
        try (HeldPermit ignored = new HeldPermit(gate)) {
            assertThat(gate.runIfFree(() -> "searched")).isEmpty();
        }

        assertThat(gate.runIfFree(() -> "searched")).contains("searched");
    }

    private static FutureTask<String> startRun(SearchGate gate, CountDownLatch queued) {
        FutureTask<String> task = new FutureTask<>(() -> gate.run(() -> "searched", queued::countDown));
        Thread.ofVirtual().start(task);
        return task;
    }
}
