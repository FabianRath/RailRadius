package com.railreach.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferWindowTest {

    private static final int MAX_WAIT_SECONDS = 3 * 3600;

    @Test
    void usableBestArrivalNeedsNoFallbackScan() {
        TransferWindow window = window();

        assertThat(window.bestFor(3600, i -> true)).isEqualTo(2);
        assertThat(window.fallbackScanCount()).isZero();
        assertThat(window.fallbackScannedArrivals()).isZero();
    }

    @Test
    void unusableBestArrivalIsReplacedByTheBestUsableOneAndCountedAsAFallbackScan() {
        TransferWindow window = window();

        assertThat(window.bestFor(3600, i -> i != 2)).isEqualTo(1);
        assertThat(window.fallbackScanCount()).isEqualTo(1);
        assertThat(window.fallbackScannedArrivals()).isEqualTo(3);
    }

    // Three arrivals before 01:00, the last one with the shortest journey so far
    private static TransferWindow window() {
        int[] arrivalSeconds = {600, 1200, 1800};
        int[] durationMinutes = {60, 60, 10};
        return new TransferWindow(arrivalSeconds, durationMinutes, 0, 0, MAX_WAIT_SECONDS);
    }
}
