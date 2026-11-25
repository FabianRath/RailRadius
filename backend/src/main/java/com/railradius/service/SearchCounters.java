package com.railradius.service;

/** What a search did, for its log: where the time goes on large data. */
final class SearchCounters {

    long stopArrivals;
    long boardings;
    long departuresScanned;
    long fallbackScans;
    long fallbackScannedArrivals;
    // Summed over the threads that chose boardings, estimated (TransferWindowInts#fallbackScanNanos)
    long fallbackScanNanos;
    long pathsNanos;
    int reachedStations;
}
