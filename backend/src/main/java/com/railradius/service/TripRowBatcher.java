package com.railradius.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Buffers stop_times rows per trip and hands whole trips to {@code flush} in batches, so big feeds don't hold all
 * stop_times in memory. A batch is only cut where a new trip starts, which keeps every trip whole as long as the
 * file is grouped by trip_id. GTFS doesn't require that order: once a trip turns up again after another one
 * started, batching is switched off for the rest of the file. Rows of a trip that was already flushed by then are
 * dropped, because the graph can't extend a trip it has already built.
 */
@Slf4j
final class TripRowBatcher<T> {

    private final int batchSize;
    // Builds the graph part of a batch and returns the number of connections added
    private final ToIntFunction<Map<String, List<T>>> flush;
    private final Map<String, List<T>> rowsByTrip = new HashMap<>();
    private final Set<String> flushedTripIds = new HashSet<>();
    private String currentTripId;
    private boolean batching = true;
    private int connectionsAdded;
    private int droppedRows;

    TripRowBatcher(int batchSize, ToIntFunction<Map<String, List<T>>> flush) {
        this.batchSize = batchSize;
        this.flush = flush;
    }

    void add(String tripId, T row) {
        if (!tripId.equals(currentTripId)) startTrip(tripId);
        if (flushedTripIds.contains(tripId)) {
            droppedRows++;
            return;
        }
        rowsByTrip.computeIfAbsent(tripId, k -> new ArrayList<>()).add(row);
    }

    int bufferedTripCount() {
        return rowsByTrip.size();
    }

    /** Flushes the remaining trips. Returns the number of connections added over all batches. */
    int finish() {
        flushBatch();
        if (droppedRows > 0) {
            log.warn("  {} stop_times rows dropped: their trip was already built when they turned up", droppedRows);
        }
        return connectionsAdded;
    }

    int tripCount() {
        return flushedTripIds.size();
    }

    private void startTrip(String tripId) {
        if (batching && (rowsByTrip.containsKey(tripId) || flushedTripIds.contains(tripId))) {
            batching = false;
            log.warn("  stop_times.txt is not grouped by trip_id; reading the rest of it in one batch");
        }
        if (batching && rowsByTrip.size() >= batchSize) {
            flushBatch();
            log.info("  ... {} trips processed total", flushedTripIds.size());
        }
        currentTripId = tripId;
    }

    private void flushBatch() {
        connectionsAdded += flush.applyAsInt(rowsByTrip);
        flushedTripIds.addAll(rowsByTrip.keySet());
        rowsByTrip.clear();
    }
}
