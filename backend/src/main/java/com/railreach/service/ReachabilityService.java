package com.railreach.service;

import com.railreach.dto.*;
import com.railreach.model.Connection;
import com.railreach.model.JourneyPath;
import com.railreach.model.Station;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReachabilityService {

    private static final int STREAM_BATCH_SIZE = 30;

    private final GtfsDataService gtfsDataService;
    private final CacheManager cacheManager;
    private HubRoutingService hubRoutingService; // Injected later to avoid circular dependency

    @Value("${railreach.algorithm.max-transfer-time-minutes}")
    private int maxTransferTimeMinutes;

    @Value("${railreach.cache.predictive-max-transfers:5}")
    private int predictiveMaxTransfers;

    @Value("${railreach.cache.predictive-enabled:true}")
    private boolean predictiveEnabled;

    // Track in-flight warming jobs to avoid duplicates
    private final Set<String> warmingInProgress = Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<>());

    // Raw BFS result cache: "stationId-transfers" -> Map<stopId, JourneyPath>
    // Stored separately from the Caffeine response cache so the BFS can seed
    // from N-1 results when computing N transfers (incremental BFS).
    private final Map<String, Map<String, JourneyPath>> rawPathCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ReachabilityService(GtfsDataService gtfsDataService, CacheManager cacheManager) {
        this.gtfsDataService = gtfsDataService;
        this.cacheManager = cacheManager;
    }

    // Setter for circular dependency injection
    public void setHubRoutingService(HubRoutingService hubRoutingService) {
        this.hubRoutingService = hubRoutingService;
    }

    @Cacheable(value = "reachability", key = "#request.startStationId + '-' + #request.maxTransfers + '-' + #request.minTransferTimeMinutes + '-' + #request.maxTransferTimeMinutes")
    public ReachabilityResponse calculateReachability(ReachabilityRequest request) {
        long startTime = System.currentTimeMillis();

        int maxTransfers = request.getMaxTransfers() != null ? request.getMaxTransfers() : 5;
        int maxTransferMins = request.getMaxTransferTimeMinutes() != null
                ? request.getMaxTransferTimeMinutes() : maxTransferTimeMinutes;
        int minTransferMins = request.getMinTransferTimeMinutes() != null
                ? request.getMinTransferTimeMinutes() : 0;

        log.info("Calculating reachability from {} with maxTransfers={}, transfer window={}–{}m",
                request.getStartStationId(), maxTransfers, minTransferMins, maxTransferMins);

        Map<String, JourneyPath> reachable = resolveReachability(
                request.getStartStationId(), maxTransfers, minTransferMins, maxTransferMins);

        if (request.getMaxJourneyTimeHours() != null) {
            int maxMins = (int) (request.getMaxJourneyTimeHours() * 60);
            reachable = reachable.entrySet().stream()
                    .filter(e -> e.getValue().getTotalDurationMinutes() <= maxMins)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        List<ReachableStation> stationList = convertToDTO(reachable);
        Station startStation = gtfsDataService.getStation(request.getStartStationId());
        long duration = System.currentTimeMillis() - startTime;

        log.info("Found {} reachable stations in {}ms", stationList.size(), duration);

        return ReachabilityResponse.builder()
                .startStationId(request.getStartStationId())
                .startStationName(startStation != null ? startStation.getName() : "Unknown")
                .maxTransfers(maxTransfers)
                .totalReachableStations(stationList.size())
                .calculationTimeMs(duration)
                .reachableStations(stationList)
                .build();
    }

    /**
     * Central dispatch: decides whether to run a full BFS, an incremental BFS
     * seeded from N-1 cached results, or a hub-routed query.
     * Also writes results into rawPathCache for future incremental use.
     */
    private Map<String, JourneyPath> resolveReachability(
            String startId, int maxTransfers, int minTransferMins, int maxTransferMins) {

        String rawKey  = startId + "-" + maxTransfers + "-" + minTransferMins + "-" + maxTransferMins;
        String prevKey = startId + "-" + (maxTransfers - 1) + "-" + minTransferMins + "-" + maxTransferMins;

        // Return raw cache hit immediately (no BFS at all)
        Map<String, JourneyPath> cached = rawPathCache.get(rawKey);
        if (cached != null) {
            log.info("  Raw cache HIT: {} x{} ({} stations) — skipping BFS",
                    startId, maxTransfers, cached.size());
            return cached;
        }

        Map<String, JourneyPath> result;

        // Can we seed from N-1 results?
        Map<String, JourneyPath> prevResult = maxTransfers > 0 ? rawPathCache.get(prevKey) : null;

        if (prevResult != null && !prevResult.isEmpty()) {
            log.info("  Incremental BFS: seeding from {} x{} ({} stations), adding 1 more transfer",
                    startId, maxTransfers - 1, prevResult.size());
            result = runIncrementalBFS(startId, prevResult, maxTransfers, minTransferMins, maxTransferMins);

        } else if (maxTransfers >= 1 && hubRoutingService != null
                && hubRoutingService.isHubRoutingAvailable()) {
            log.info("  Hub routing: {} x{}", startId, maxTransfers);
            result = hubRoutingService.queryViaHubs(startId, maxTransfers, minTransferMins, maxTransferMins);

        } else {
            log.info("  Full BFS: {} x{}", startId, maxTransfers);
            result = runBFSInternal(startId, maxTransfers, minTransferMins, maxTransferMins);
        }

        // Store raw result for future incremental use
        rawPathCache.put(rawKey, result);
        log.info("  Cached raw result: {} x{} ({} stations)", startId, maxTransfers, result.size());
        return result;
    }

    /**
     * Incremental BFS: takes the already-computed N-1 transfer result and explores
     * exactly one more transfer layer on top. Stations already known at ≤N-1 transfers
     * are kept as-is; we only look for genuinely new stations reachable via one more
     * transfer from any N-1 endpoint.
     *
     * This turns a full O(E·T^N) BFS into a much smaller O(E·T) pass over only the
     * frontier stations (those reached with exactly N-1 transfers).
     */
    private Map<String, JourneyPath> runIncrementalBFS(
            String startId,
            Map<String, JourneyPath> prevResult,
            int maxTransfers,
            int minTransferMins,
            int maxTransferMins) {

        // Start with a copy of all known stations from the N-1 result
        Map<String, JourneyPath> reachable = new HashMap<>(prevResult);

        Queue<QueueItem> queue = new LinkedList<>();
        Set<String> exploredBoardings = new HashSet<>();
        int queueItemsProcessed = 0;
        int queueItemsSkipped = 0;

        // Seed the queue from every station reached with exactly N-1 transfers.
        // These are the only stations that can initiate the final transfer.
        int frontier = 0;
        for (Map.Entry<String, JourneyPath> e : prevResult.entrySet()) {
            JourneyPath jp = e.getValue();
            if (jp.getTransfers() == maxTransfers - 1) {
                String currentTripId = jp.getConnections().isEmpty() ? null
                        : jp.getConnections().get(jp.getConnections().size() - 1).getTripId();
                queue.offer(new QueueItem(
                        e.getKey(), jp.getTransfers(),
                        jp.getStationIds(), jp.getConnections(),
                        currentTripId, jp.getTotalDurationMinutes()));
                frontier++;
            }
        }
        log.info("  Incremental BFS frontier: {} stations with exactly {} transfers",
                frontier, maxTransfers - 1);

        while (!queue.isEmpty()) {
            QueueItem cur = queue.poll();
            // Only allow one more transfer from frontier stations
            if (cur.transfers >= maxTransfers) continue;

            queueItemsProcessed++;

            Set<String> trips = gtfsDataService.getTripsAtStop(cur.stationId);

            for (String tripId : trips) {
                // Every trip from a frontier station is a transfer (or continuation)
                boolean isContinuation = tripId.equals(cur.currentTripId);
                int nextTransfers = isContinuation ? cur.transfers : cur.transfers + 1;
                if (nextTransfers > maxTransfers) continue;

                // Validate transfer timing if this is a real transfer
                if (!isContinuation && !cur.connections.isEmpty()) {
                    int arrivalSec = cur.connections.get(cur.connections.size() - 1)
                            .getArrivalTimeSeconds();
                    int curIdx = gtfsDataService.getStopIndexOnTrip(tripId, cur.stationId);
                    if (curIdx < 0) continue;
                    List<String> newTripStops = gtfsDataService.getStopsOnTrip(tripId);
                    if (curIdx >= newTripStops.size() - 1) continue;

                    Connection firstConn = gtfsDataService.getConnectionByTrip(
                            cur.stationId, newTripStops.get(curIdx + 1), tripId);
                    if (firstConn == null) continue;

                    int waitMins = (firstConn.getDepartureTimeSeconds() - arrivalSec) / 60;
                    if (waitMins < -60) waitMins += 24 * 60;
                    if (waitMins < minTransferMins || waitMins > maxTransferMins) continue;
                }

                // Boarding deduplication
                String boardingKey = cur.stationId + "|" + tripId + "|" + nextTransfers;
                if (!exploredBoardings.add(boardingKey)) {
                    queueItemsSkipped++;
                    continue;
                }

                int startIdx = gtfsDataService.getStopIndexOnTrip(tripId, cur.stationId);
                if (startIdx < 0) continue;
                List<String> stopSequence = gtfsDataService.getStopsOnTrip(tripId);
                if (startIdx >= stopSequence.size() - 1) continue;

                List<Connection> runningConns = new ArrayList<>(cur.connections);
                List<String>     runningPath  = new ArrayList<>(cur.path);
                int runningDur = cur.durationMinutes;
                int prevArrSec = runningConns.isEmpty() ? -1
                        : runningConns.get(runningConns.size() - 1).getArrivalTimeSeconds();

                for (int si = startIdx + 1; si < stopSequence.size(); si++) {
                    String segFrom = stopSequence.get(si - 1);
                    String segTo   = stopSequence.get(si);
                    Connection conn = gtfsDataService.getConnectionByTrip(segFrom, segTo, tripId);
                    if (conn == null) break;

                    int depSec = conn.getDepartureTimeSeconds();
                    int arrSec = conn.getArrivalTimeSeconds();
                    if (prevArrSec >= 0) {
                        int waitMins = (depSec - prevArrSec) / 60;
                        if (waitMins < -60) waitMins += 24 * 60;
                        runningDur += Math.max(0, waitMins);
                    }
                    int segMins = (arrSec - depSec) / 60;
                    if (segMins < -60) segMins += 24 * 60;
                    runningDur += Math.max(0, segMins);
                    prevArrSec = arrSec;

                    runningConns.add(conn);
                    String nextStopId = stopSequence.get(si);
                    runningPath.add(nextStopId);
                    if (nextStopId.equals(startId)) continue;

                    JourneyPath existingPath = reachable.get(nextStopId);
                    boolean better = existingPath == null
                            || nextTransfers < existingPath.getTransfers()
                            || (nextTransfers == existingPath.getTransfers()
                                && runningDur < existingPath.getTotalDurationMinutes());

                    if (better) {
                        List<Connection> storedConns = new ArrayList<>(runningConns);
                        List<String>     storedPath  = new ArrayList<>(runningPath);
                        JourneyPath jp = JourneyPath.builder()
                                .stationIds(storedPath)
                                .connections(storedConns)
                                .transfers(nextTransfers)
                                .totalDurationMinutes(runningDur)
                                .build();
                        reachable.put(nextStopId, jp);

                        // Only push to queue if we haven't yet hit maxTransfers
                        if (nextTransfers < maxTransfers) {
                            queue.offer(new QueueItem(nextStopId, nextTransfers,
                                    storedPath, storedConns, tripId, runningDur));
                        }
                    }
                }
            }
        }

        int totalItems = queueItemsProcessed + queueItemsSkipped;
        double skipRate = totalItems > 0 ? (queueItemsSkipped * 100.0 / totalItems) : 0;
        int newStations = reachable.size() - prevResult.size();
        log.info("  Incremental BFS done: +{} new stations (total {}), {}% pruned",
                newStations, reachable.size(), String.format("%.1f", skipRate));

        return reachable;
    }

    /**
     * Internal BFS implementation, accessible to HubRoutingService.
     * Package-private to allow hub routing to use it.
     */
    Map<String, JourneyPath> runBFSInternal(String startId, int maxTransfers, int maxTransferMins) {
        return runBFSInternal(startId, maxTransfers, 0, maxTransferMins);
    }

    Map<String, JourneyPath> runBFSInternal(String startId, int maxTransfers,
                                             int minTransferMins, int maxTransferMins) {
        Map<String, JourneyPath> reachable = new HashMap<>();
        Queue<QueueItem> queue = new LinkedList<>();
        int lastLoggedCount = 0;
        int queueItemsProcessed = 0;
        int queueItemsSkipped = 0;

        // FIX 1: Track explored (stationId, tripId) boarding pairs.
        // Once we have walked trip T departing from station S, no later queue item
        // should repeat that walk — even if it arrives at S via a different route.
        // This eliminates the O(queue_size × trips_per_station) blowup at hubs.
        Set<String> exploredBoardings = new HashSet<>();

        reachable.put(startId, JourneyPath.builder()
                .stationIds(List.of(startId))
                .connections(new ArrayList<>())
                .transfers(0)
                .totalDurationMinutes(0)
                .build());
        queue.offer(new QueueItem(startId, 0, List.of(startId), new ArrayList<>(), null, 0));

        while (!queue.isEmpty()) {
            QueueItem cur = queue.poll();
            if (cur.transfers > maxTransfers) continue;

            // Prune: skip if we already have a strictly better path to this station
            JourneyPath existing = reachable.get(cur.stationId);
            if (existing != null) {
                if (existing.getTransfers() < cur.transfers ||
                    (existing.getTransfers() == cur.transfers
                            && existing.getTotalDurationMinutes() < cur.durationMinutes)) {
                    queueItemsSkipped++;
                    continue;
                }
            }

            queueItemsProcessed++;

            Set<String> trips = gtfsDataService.getTripsAtStop(cur.stationId);

            if (reachable.size() - lastLoggedCount >= 50) {
                log.info("  ... {} stations reachable (queue={})", reachable.size(), queue.size());
                lastLoggedCount = reachable.size();
            }

            for (String tripId : trips) {
                boolean isNewTrip = !tripId.equals(cur.currentTripId);
                int nextTransfers = cur.transfers;

                if (isNewTrip && cur.currentTripId != null) {
                    nextTransfers = cur.transfers + 1;
                    if (nextTransfers > maxTransfers) continue;

                    // FIX 2: O(1) stop-index lookup instead of List.indexOf()
                    int curStopIdx = gtfsDataService.getStopIndexOnTrip(tripId, cur.stationId);
                    if (curStopIdx < 0) continue;

                    List<String> newTripStops = gtfsDataService.getStopsOnTrip(tripId);
                    if (curStopIdx >= newTripStops.size() - 1) continue;

                    if (!cur.connections.isEmpty()) {
                        int arrivalTimeSeconds = cur.connections.get(cur.connections.size() - 1)
                                .getArrivalTimeSeconds();

                        // FIX 3: O(1) connection-by-trip lookup instead of stream filter
                        String nextStop = newTripStops.get(curStopIdx + 1);
                        Connection newConn = gtfsDataService.getConnectionByTrip(
                                cur.stationId, nextStop, tripId);
                        if (newConn == null) continue;

                        int waitTimeMins = (newConn.getDepartureTimeSeconds() - arrivalTimeSeconds) / 60;
                        if (waitTimeMins < -60) waitTimeMins += 24 * 60;
                        if (waitTimeMins < minTransferMins || waitTimeMins > maxTransferMins) continue;
                    }
                } else if (isNewTrip) {
                    nextTransfers = 0;
                }
                if (nextTransfers > maxTransfers) continue;

                // FIX 1 cont: skip this (station, trip) boarding if already explored
                String boardingKey = cur.stationId + "|" + tripId + "|" + nextTransfers;
                if (!exploredBoardings.add(boardingKey)) {
                    queueItemsSkipped++;
                    continue;
                }

                // FIX 2: O(1) position lookup
                int startIdx = isNewTrip
                        ? gtfsDataService.getStopIndexOnTrip(tripId, cur.stationId)
                        : gtfsDataService.getStopIndexOnTrip(tripId, cur.stationId);
                if (startIdx < 0) continue;

                List<String> stopSequence = gtfsDataService.getStopsOnTrip(tripId);
                if (startIdx >= stopSequence.size() - 1) continue;

                List<Connection> runningConns = new ArrayList<>(cur.connections);
                List<String>     runningPath  = new ArrayList<>(cur.path);

                // FIX 4: track duration incrementally — no totalDuration() re-scan per step
                int runningDurMinutes = cur.durationMinutes;
                int prevArrivalSeconds = runningConns.isEmpty() ? -1
                        : runningConns.get(runningConns.size() - 1).getArrivalTimeSeconds();

                for (int si = startIdx + 1; si < stopSequence.size(); si++) {
                    String segFrom = stopSequence.get(si - 1);
                    String segTo   = stopSequence.get(si);

                    // FIX 3: O(1) connection lookup
                    Connection conn = gtfsDataService.getConnectionByTrip(segFrom, segTo, tripId);
                    if (conn == null) break; // gap in data

                    // FIX 4: incremental duration
                    int depSec = conn.getDepartureTimeSeconds();
                    int arrSec = conn.getArrivalTimeSeconds();
                    if (prevArrivalSeconds >= 0) {
                        // Transfer wait (0 if continuing same trip)
                        int waitMins = (depSec - prevArrivalSeconds) / 60;
                        if (waitMins < -60) waitMins += 24 * 60;
                        runningDurMinutes += Math.max(0, waitMins);
                    }
                    int segMins = (arrSec - depSec) / 60;
                    if (segMins < -60) segMins += 24 * 60;
                    runningDurMinutes += Math.max(0, segMins);
                    prevArrivalSeconds = arrSec;

                    runningConns.add(conn);
                    String nextStopId = stopSequence.get(si);
                    runningPath.add(nextStopId);

                    if (nextStopId.equals(startId)) continue;

                    JourneyPath existingPath = reachable.get(nextStopId);
                    boolean better = existingPath == null
                            || nextTransfers < existingPath.getTransfers()
                            || (nextTransfers == existingPath.getTransfers()
                                && runningDurMinutes < existingPath.getTotalDurationMinutes());

                    if (better) {
                        List<Connection> storedConns = new ArrayList<>(runningConns);
                        List<String>     storedPath  = new ArrayList<>(runningPath);

                        JourneyPath jp = JourneyPath.builder()
                                .stationIds(storedPath)
                                .connections(storedConns)
                                .transfers(nextTransfers)
                                .totalDurationMinutes(runningDurMinutes)
                                .build();
                        reachable.put(nextStopId, jp);

                        queue.offer(new QueueItem(nextStopId, nextTransfers,
                                storedPath, storedConns, tripId, runningDurMinutes));
                    }
                }
            }
        }

        int totalQueueItems = queueItemsProcessed + queueItemsSkipped;
        double skipRate = totalQueueItems > 0 ? (queueItemsSkipped * 100.0 / totalQueueItems) : 0;
        log.info("  BFS stats: {} queue items ({} processed, {} skipped = {}% pruned), {} stations",
                totalQueueItems, queueItemsProcessed, queueItemsSkipped,
                String.format("%.1f", skipRate), reachable.size());

        return reachable;
    }

    /**
     * Schedules background pre-computation for N+1, N+2, ... transfers
     * so the cache is warm when the user moves the slider up.
     */
    @Async
    public void warmHigherTransferCounts(ReachabilityRequest original,
                                          int currentMaxTransfers,
                                          int maxTransferMins) {
        String stationId = original.getStartStationId();

        for (int t = currentMaxTransfers + 1; t <= predictiveMaxTransfers; t++) {
            String cacheKey    = stationId + "-" + t + "-0-" + maxTransferMins;
            String prevRawKey  = stationId + "-" + (t - 1) + "-0-" + maxTransferMins;

            // Skip if rawPathCache already has this result (resolveReachability will hit it instantly)
            if (rawPathCache.containsKey(cacheKey)) {
                log.debug("  Predictive cache: {} x{} already in rawPathCache", stationId, t);
                continue;
            }

            // CRITICAL: don't warm N+1 unless N is already in rawPathCache.
            // If it isn't, the warmer would fall back to a full BFS and cache a result
            // that can't benefit from incremental expansion — causing stale Caffeine entries.
            if (t > 1 && !rawPathCache.containsKey(prevRawKey)) {
                log.debug("  Predictive cache: skipping {} x{} — x{} not yet in rawPathCache",
                        stationId, t, t - 1);
                break; // No point trying higher values either
            }

            // Skip if already warming
            if (!warmingInProgress.add(cacheKey)) {
                log.debug("  Predictive cache: {} x{} already warming", stationId, t);
                continue;
            }

            final int transfers = t;
            try {
                log.info("  Predictive cache warming: {} x{} transfers (incremental from x{})",
                        stationId, transfers, transfers - 1);
                long start = System.currentTimeMillis();

                ReachabilityRequest warmRequest = ReachabilityRequest.builder()
                        .startStationId(stationId)
                        .maxTransfers(transfers)
                        .maxJourneyTimeHours(original.getMaxJourneyTimeHours())
                        .maxTransferTimeMinutes(maxTransferMins)
                        .build();

                // resolveReachability (called inside) will use incremental BFS since
                // prevRawKey is guaranteed to be in rawPathCache at this point.
                calculateReachability(warmRequest);

                long duration = System.currentTimeMillis() - start;
                log.info("  Predictive cache warmed: {} x{} transfers in {}ms",
                        stationId, transfers, duration);
            } catch (Exception e) {
                log.warn("  Predictive cache warming failed for {} x{}: {}",
                        stationId, transfers, e.getMessage());
            } finally {
                warmingInProgress.remove(cacheKey);
            }
        }
    }

    /**
     * Returns stats about what is currently warm in the cache.
     */
    public Map<String, Object> getCacheStats() {
        org.springframework.cache.Cache cache = cacheManager.getCache("reachability");
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("warmingInProgress", new ArrayList<>(warmingInProgress));
        stats.put("warmingCount", warmingInProgress.size());
        if (cache instanceof org.springframework.cache.caffeine.CaffeineCache caffeineCache) {
            com.github.benmanes.caffeine.cache.stats.CacheStats caffeStats =
                    caffeineCache.getNativeCache().stats();
            stats.put("hitCount",      caffeStats.hitCount());
            stats.put("missCount",     caffeStats.missCount());
            stats.put("hitRate",       String.format("%.1f%%", caffeStats.hitRate() * 100));
            stats.put("evictionCount", caffeStats.evictionCount());
            stats.put("estimatedSize", caffeineCache.getNativeCache().estimatedSize());
        }
        stats.put("rawPathCacheEntries", rawPathCache.size());
        stats.put("rawPathCacheKeys", new ArrayList<>(rawPathCache.keySet()));
        return stats;
    }

    /** Clears the raw path cache (call after GTFS data reload). */
    public void clearRawCache() {
        rawPathCache.clear();
        log.info("Raw path cache cleared");
    }

    /**
     * Streaming version: resolves reachability, then sends the result to the
     * provided SseEmitter in batches. Designed to be called from a dedicated
     * thread so the HTTP response stays open until BFS completes.
     */
    public void calculateReachabilityStreaming(ReachabilityRequest request, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        int maxTransfers = request.getMaxTransfers() != null ? request.getMaxTransfers() : 5;
        int maxTransferMins = request.getMaxTransferTimeMinutes() != null
                ? request.getMaxTransferTimeMinutes() : maxTransferTimeMinutes;
        int minTransferMins = request.getMinTransferTimeMinutes() != null
                ? request.getMinTransferTimeMinutes() : 0;

        // NOTE: Cache lookup is handled entirely by resolveReachability() via rawPathCache.
        // We intentionally do NOT check the Caffeine response cache here — it can hold
        // stale results (e.g. from predictive warming before incremental BFS ran).

        try {
            // Stream only the finished result: the BFS improves paths as it goes, so emitting
            // during the search would send stations more than once and with non-final paths.
            Map<String, JourneyPath> reachable = resolveReachability(
                    request.getStartStationId(), maxTransfers, minTransferMins, maxTransferMins);
            List<ReachableStation> stations = toStreamedStations(request, reachable);
            for (int i = 0; i < stations.size(); i += STREAM_BATCH_SIZE) {
                flushBatch(emitter, stations.subList(i, Math.min(i + STREAM_BATCH_SIZE, stations.size())));
            }

            // Evict any stale Caffeine entry so calculateReachability() re-populates it
            // with the correct incremental result next time it's called (e.g. from warmer).
            org.springframework.cache.Cache caffeineCache = cacheManager.getCache("reachability");
            if (caffeineCache != null) {
                caffeineCache.evict(request.getStartStationId() + "-" + maxTransfers + "-" + minTransferMins + "-" + maxTransferMins);
            }

            long duration = System.currentTimeMillis() - startTime;
            // Send done event
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of(
                        "total", stations.size(),
                        "calculationTimeMs", duration
                    )));
            emitter.complete();

            // Warm up higher transfer counts in background
            if (predictiveEnabled) {
                warmHigherTransferCounts(request, maxTransfers, maxTransferMins);
            }

        } catch (Exception e) {
            log.error("Streaming BFS error: {}", e.getMessage(), e);
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (Exception ignored) {}
            emitter.completeWithError(e);
        }
    }

    /** The start station itself is not a destination, so it is never streamed. */
    private List<ReachableStation> toStreamedStations(ReachabilityRequest request,
                                                      Map<String, JourneyPath> reachable) {
        Integer maxJourneyMins = request.getMaxJourneyTimeHours() != null
                ? (int) (request.getMaxJourneyTimeHours() * 60) : null;
        List<ReachableStation> stations = new ArrayList<>();
        reachable.forEach((stopId, path) -> {
            if (stopId.equals(request.getStartStationId())) return;
            if (maxJourneyMins != null && path.getTotalDurationMinutes() > maxJourneyMins) return;
            Station station = gtfsDataService.getStation(stopId);
            if (station == null) return;
            stations.add(toReachableStation(stopId, path, station));
        });
        return stations;
    }

    private void flushBatch(SseEmitter emitter, List<ReachableStation> stations) {
        try {
            emitter.send(SseEmitter.event()
                    .name("stations")
                    .data(stations));
        } catch (Exception e) {
            log.warn("SSE flush failed: {}", e.getMessage());
        }
    }

    private ReachableStation toReachableStation(String stopId, JourneyPath path, Station station) {
        List<JourneyLeg> legs = buildLegs(path.getConnections());
        List<String> pathNames = path.getStationIds().stream()
                .map(id -> {
                    Station s = gtfsDataService.getStation(id);
                    return s != null ? s.getName() : id;
                })
                .collect(Collectors.toList());
        return ReachableStation.builder()
                .stopId(stopId)
                .stopName(station.getName())
                .latitude(station.getLatitude())
                .longitude(station.getLongitude())
                .transfers(path.getTransfers())
                .totalDuration(formatDuration(path.getTotalDurationMinutes()))
                .totalDurationMinutes(path.getTotalDurationMinutes())
                .path(path.getStationIds())
                .pathNames(pathNames)
                .legs(legs)
                .build();
    }

    private List<ReachableStation> convertToDTO(Map<String, JourneyPath> reachable) {
        return reachable.entrySet().stream()
                .map(e -> {
                    String stopId = e.getKey();
                    JourneyPath path = e.getValue();
                    Station station = gtfsDataService.getStation(stopId);
                    if (station == null) return null;

                    List<JourneyLeg> legs = buildLegs(path.getConnections());
                    List<String> pathNames = path.getStationIds().stream()
                            .map(id -> {
                                Station s = gtfsDataService.getStation(id);
                                return s != null ? s.getName() : id;
                            })
                            .collect(Collectors.toList());

                    return ReachableStation.builder()
                            .stopId(stopId)
                            .stopName(station.getName())
                            .latitude(station.getLatitude())
                            .longitude(station.getLongitude())
                            .transfers(path.getTransfers())
                            .totalDuration(formatDuration(path.getTotalDurationMinutes()))
                            .totalDurationMinutes(path.getTotalDurationMinutes())
                            .path(path.getStationIds())
                            .pathNames(pathNames)
                            .legs(legs)
                            .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<JourneyLeg> buildLegs(List<Connection> connections) {
        List<JourneyLeg> legs = new ArrayList<>();
        if (connections.isEmpty()) return legs;

        // Merge consecutive connections that are part of the same train ride.
        // Two connections are "same ride" if:
        //   (a) same tripId, OR
        //   (b) same route name AND the arrival of one equals the departure of the next
        //       (handles same train across different trip_id variants)
        int i = 0;
        while (i < connections.size()) {
            Connection segStart = connections.get(i);
            String trainName = segStart.getRouteName();
            int depTimeSeconds = segStart.getDepartureTimeSeconds();  // Use int directly
            String fromStop  = segStart.getFromStopId();

            int j = i + 1;
            while (j < connections.size()) {
                Connection prev = connections.get(j - 1);
                Connection next = connections.get(j);
                boolean sameTrip  = prev.getTripId().equals(next.getTripId());
                boolean sameName  = prev.getRouteName() != null
                        && prev.getRouteName().equals(next.getRouteName());
                // "Continuous" means the train arrives at the same time as the next departs
                // (or within 1 minute - allow for rounding)
                boolean continuous = timeDiff(prev.getArrivalTimeSeconds(), next.getDepartureTimeSeconds()) <= 1;

                if (sameTrip || (sameName && continuous)) {
                    j++;
                } else {
                    break;
                }
            }

            // connections[i..j-1] are all the same train ride
            Connection segEnd = connections.get(j - 1);
            int dur = timeDiff(depTimeSeconds, segEnd.getArrivalTimeSeconds());

            legs.add(JourneyLeg.builder()
                    .type("TRAIN")
                    .trainName(trainName)
                    .fromStation(stationName(fromStop))
                    .toStation(stationName(segEnd.getToStopId()))
                    .departureTime(segStart.getDepartureTime())  // Getter formats the int as string
                    .arrivalTime(segEnd.getArrivalTime())         // Getter formats the int as string
                    .duration(formatDuration(dur))
                    .durationMinutes(dur)
                    .build());

            // Add TRANSFER between this ride and the next
            if (j < connections.size()) {
                Connection nextSeg = connections.get(j);
                // nextSeg is the first connection of the next train leg,
                // departing FROM the transfer station — correct departure time
                int transferTimeSeconds = nextSeg.getDepartureTimeSeconds() - segEnd.getArrivalTimeSeconds();
                int transferTimeMins = transferTimeSeconds / 60;
                if (transferTimeMins < -60) transferTimeMins += 24 * 60; // overnight wrap
                String transferStation = stationName(segEnd.getToStopId());

                legs.add(JourneyLeg.builder()
                        .type("TRANSFER")
                        .fromStation(transferStation)
                        .toStation(transferStation)
                        .duration(formatDuration(Math.max(0, transferTimeMins)))
                        .durationMinutes(Math.max(0, transferTimeMins))
                        .build());
            }

            i = j;
        }

        return legs;
    }

    private String stationName(String stopId) {
        Station s = gtfsDataService.getStation(stopId);
        return s != null ? s.getName() : stopId;
    }

    private int timeDiff(int fromSeconds, int toSeconds) {
        int diffSeconds = toSeconds - fromSeconds;
        int diffMins = diffSeconds / 60;
        // Allow wraparound only for small values (overnight trains, max 4h wrap)
        // For transfer time validation we want the raw difference
        if (diffMins < -60) diffMins += 24 * 60; // only wrap if more than 1h negative (genuine overnight)
        return diffMins;
    }

    private String formatDuration(int minutes) {
        if (minutes < 0) minutes = 0;
        int h = minutes / 60, m = minutes % 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private static class QueueItem {
        final String stationId;
        final int transfers;
        final List<String> path;
        final List<Connection> connections;
        final String currentTripId;
        final int durationMinutes; // carried to avoid recomputing totalDuration()

        QueueItem(String stationId, int transfers, List<String> path,
                  List<Connection> connections, String currentTripId, int durationMinutes) {
            this.stationId = stationId;
            this.transfers = transfers;
            this.path = path;
            this.connections = connections;
            this.currentTripId = currentTripId;
            this.durationMinutes = durationMinutes;
        }
    }
}
