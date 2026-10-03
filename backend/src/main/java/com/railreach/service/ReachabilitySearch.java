package com.railreach.service;

import com.railreach.model.Connection;
import com.railreach.model.JourneyPath;
import com.railreach.util.GtfsTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Transfer-bounded trip BFS over the {@link TransitGraph}. Keeps one best path per station,
 * ranked by (transfers, total duration).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReachabilitySearch {

    private static final int PROGRESS_LOG_INTERVAL_STATIONS = 50;

    private final TransitGraph transitGraph;

    /** durationMinutes is carried along so it never has to be recomputed from the connections. */
    private record QueueItem(String stationId, int transfers, List<String> path,
                             List<Connection> connections, String currentTripId, int durationMinutes) {}

    private record SearchState(String startId, int maxTransfers, Map<String, JourneyPath> reachable,
                               Queue<QueueItem> queue) {}

    /** Every station reachable from startId with at most maxTransfers, including startId itself. */
    public Map<String, JourneyPath> fullSearch(String startId, int maxTransfers,
                                               int minTransferMins, int maxTransferMins) {
        SearchState state = new SearchState(startId, maxTransfers, new HashMap<>(), new ArrayDeque<>());
        // A (station, trip, transfers) boarding is walked once, however many paths reach the station
        Set<String> exploredBoardings = new HashSet<>();
        int lastLoggedCount = 0;
        int processed = 0;
        int skipped = 0;

        state.reachable().put(startId, JourneyPath.builder()
                .stationIds(List.of(startId))
                .connections(new ArrayList<>())
                .transfers(0)
                .totalDurationMinutes(0)
                .build());
        state.queue().offer(new QueueItem(startId, 0, List.of(startId), new ArrayList<>(), null, 0));

        while (!state.queue().isEmpty()) {
            QueueItem cur = state.queue().poll();
            if (isWorseThanKnown(cur, state.reachable().get(cur.stationId()))) {
                skipped++;
                continue;
            }
            processed++;
            if (state.reachable().size() - lastLoggedCount >= PROGRESS_LOG_INTERVAL_STATIONS) {
                log.debug("  ... {} stations reachable (queue={})", state.reachable().size(), state.queue().size());
                lastLoggedCount = state.reachable().size();
            }

            for (String tripId : transitGraph.getTripsAtStop(cur.stationId())) {
                boolean isTransfer = cur.currentTripId() != null && !tripId.equals(cur.currentTripId());
                int nextTransfers = isTransfer ? cur.transfers() + 1 : cur.transfers();
                if (nextTransfers > maxTransfers) continue;
                if (isTransfer && !isTransferFeasible(cur, tripId, minTransferMins, maxTransferMins)) continue;
                if (!exploredBoardings.add(boardingKey(cur, tripId, nextTransfers))) {
                    skipped++;
                    continue;
                }
                rideTrip(state, cur, tripId, nextTransfers);
            }
        }

        log.info("  BFS stats: {} queue items ({} processed, {} skipped = {}% pruned), {} stations",
                processed + skipped, processed, skipped,
                String.format("%.1f", skipRatePercent(processed, skipped)), state.reachable().size());
        return state.reachable();
    }

    /**
     * Extends a finished (maxTransfers - 1) result by one transfer. Only stations reached with exactly
     * maxTransfers - 1 transfers can start the extra transfer, so the search only walks that frontier
     * instead of the whole network again. Stations already known keep their paths unless beaten.
     */
    public Map<String, JourneyPath> incrementalSearch(String startId, Map<String, JourneyPath> previous,
                                                      int maxTransfers, int minTransferMins, int maxTransferMins) {
        SearchState state = new SearchState(startId, maxTransfers, new HashMap<>(previous), new ArrayDeque<>());
        Set<String> exploredBoardings = new HashSet<>();
        int processed = 0;
        int skipped = 0;

        previous.forEach((stopId, path) -> {
            if (path.getTransfers() == maxTransfers - 1) state.queue().offer(frontierItem(stopId, path));
        });
        log.info("  Incremental BFS frontier: {} stations with exactly {} transfers",
                state.queue().size(), maxTransfers - 1);

        while (!state.queue().isEmpty()) {
            QueueItem cur = state.queue().poll();
            processed++;
            for (String tripId : transitGraph.getTripsAtStop(cur.stationId())) {
                boolean isContinuation = tripId.equals(cur.currentTripId());
                int nextTransfers = isContinuation ? cur.transfers() : cur.transfers() + 1;
                if (nextTransfers > maxTransfers) continue;
                if (!isContinuation && !cur.connections().isEmpty()
                        && !isTransferFeasible(cur, tripId, minTransferMins, maxTransferMins)) continue;
                if (!exploredBoardings.add(boardingKey(cur, tripId, nextTransfers))) {
                    skipped++;
                    continue;
                }
                rideTrip(state, cur, tripId, nextTransfers);
            }
        }

        log.info("  Incremental BFS done: +{} new stations (total {}), {}% pruned",
                state.reachable().size() - previous.size(), state.reachable().size(),
                String.format("%.1f", skipRatePercent(processed, skipped)));
        return state.reachable();
    }

    private static QueueItem frontierItem(String stopId, JourneyPath path) {
        List<Connection> connections = path.getConnections();
        String currentTripId = connections.isEmpty() ? null : connections.getLast().getTripId();
        return new QueueItem(stopId, path.getTransfers(), path.getStationIds(), connections,
                currentTripId, path.getTotalDurationMinutes());
    }

    private static boolean isWorseThanKnown(QueueItem item, JourneyPath known) {
        if (known == null) return false;
        return known.getTransfers() < item.transfers()
                || (known.getTransfers() == item.transfers()
                    && known.getTotalDurationMinutes() < item.durationMinutes());
    }

    /** The trip continues past the station and departs within the allowed transfer window. */
    private boolean isTransferFeasible(QueueItem cur, String tripId, int minTransferMins, int maxTransferMins) {
        int stopIdx = transitGraph.getStopIndexOnTrip(tripId, cur.stationId());
        if (stopIdx < 0) return false;
        List<String> tripStops = transitGraph.getStopsOnTrip(tripId);
        if (stopIdx >= tripStops.size() - 1) return false;
        if (cur.connections().isEmpty()) return true;

        Connection departure = transitGraph.getConnectionByTrip(cur.stationId(), tripStops.get(stopIdx + 1), tripId);
        if (departure == null) return false;
        int waitMins = GtfsTime.diffMinutes(cur.connections().getLast().getArrivalTimeSeconds(),
                departure.getDepartureTimeSeconds());
        return waitMins >= minTransferMins && waitMins <= maxTransferMins;
    }

    /** Rides the trip from cur's station to its last stop, recording every stop the ride improves. */
    private void rideTrip(SearchState state, QueueItem cur, String tripId, int transfers) {
        int boardIdx = transitGraph.getStopIndexOnTrip(tripId, cur.stationId());
        if (boardIdx < 0) return;
        List<String> tripStops = transitGraph.getStopsOnTrip(tripId);
        if (boardIdx >= tripStops.size() - 1) return;

        List<Connection> connections = new ArrayList<>(cur.connections());
        List<String> path = new ArrayList<>(cur.path());
        int durationMinutes = cur.durationMinutes();
        int prevArrivalSeconds = connections.isEmpty() ? -1 : connections.getLast().getArrivalTimeSeconds();

        for (int i = boardIdx + 1; i < tripStops.size(); i++) {
            Connection conn = transitGraph.getConnectionByTrip(tripStops.get(i - 1), tripStops.get(i), tripId);
            if (conn == null) break;
            // Waiting time at the boarding stop (transfer or dwell) counts towards the journey
            if (prevArrivalSeconds >= 0) {
                durationMinutes += Math.max(0, GtfsTime.diffMinutes(prevArrivalSeconds, conn.getDepartureTimeSeconds()));
            }
            durationMinutes += Math.max(0,
                    GtfsTime.diffMinutes(conn.getDepartureTimeSeconds(), conn.getArrivalTimeSeconds()));
            prevArrivalSeconds = conn.getArrivalTimeSeconds();
            connections.add(conn);
            String stopId = tripStops.get(i);
            path.add(stopId);
            if (stopId.equals(state.startId())) continue;

            if (isBetter(transfers, durationMinutes, state.reachable().get(stopId))) {
                List<Connection> storedConnections = new ArrayList<>(connections);
                List<String> storedPath = new ArrayList<>(path);
                state.reachable().put(stopId, JourneyPath.builder()
                        .stationIds(storedPath)
                        .connections(storedConnections)
                        .transfers(transfers)
                        .totalDurationMinutes(durationMinutes)
                        .build());
                // At the transfer limit the station can only continue on the same trip, which this ride covers
                if (transfers < state.maxTransfers()) {
                    state.queue().offer(new QueueItem(stopId, transfers, storedPath, storedConnections,
                            tripId, durationMinutes));
                }
            }
        }
    }

    private static boolean isBetter(int transfers, int durationMinutes, JourneyPath known) {
        return known == null
                || transfers < known.getTransfers()
                || (transfers == known.getTransfers() && durationMinutes < known.getTotalDurationMinutes());
    }

    private static String boardingKey(QueueItem cur, String tripId, int transfers) {
        return cur.stationId() + "|" + tripId + "|" + transfers;
    }

    private static double skipRatePercent(int processed, int skipped) {
        int total = processed + skipped;
        return total > 0 ? skipped * 100.0 / total : 0;
    }
}
