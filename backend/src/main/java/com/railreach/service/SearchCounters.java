package com.railreach.service;

/** What a search did, for its log: where the time goes on large data. */
final class SearchCounters {

    // Counted by the compact search only; the legacy one counts its arrival lists
    long stopArrivals;
    long boardings;
    long departuresScanned;
    long fallbackScans;
    long fallbackScannedArrivals;
    long pathsNanos;
    int reachedStations;

    void addFallbacks(TransferWindow window) {
        fallbackScans += window.fallbackScanCount();
        fallbackScannedArrivals += window.fallbackScannedArrivals();
    }
}
