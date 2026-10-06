package com.railreach.service;

/** What a search did, for its log: where the time goes on large data. */
final class SearchCounters {

    long departuresScanned;
    long fallbackScans;
    long fallbackScannedArrivals;
    long pathsNanos;

    void addFallbacks(TransferWindow window) {
        fallbackScans += window.fallbackScanCount();
        fallbackScannedArrivals += window.fallbackScannedArrivals();
    }
}
