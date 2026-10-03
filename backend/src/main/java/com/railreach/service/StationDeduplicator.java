package com.railreach.service;

import com.railreach.model.Station;
import com.railreach.util.Coordinates;
import com.railreach.util.StopIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Merges stations that are the same place after all feeds are loaded: exact-name duplicates within
 * one feed, and similar-name stations of different feeds at a border. The better connected one survives.
 */
@Component
@Slf4j
public class StationDeduplicator {

    // ~200 m
    private static final double WITHIN_FEED_RADIUS_DEG = 0.002;
    // ~500 m
    private static final double CROSS_BORDER_RADIUS_DEG = 0.005;
    // Cross-border candidates are bucketed into ~1 km cells; only neighbouring cells are compared
    private static final double CROSS_BORDER_CELL_SIZE_DEG = 0.01;
    // 0.4 still accepts "Paris Gare du Nord" vs "Paris Nord (FR)"
    private static final double CROSS_BORDER_MIN_NAME_SIMILARITY = 0.4;

    void deduplicate(TransitGraph graph) {
        log.info("=== Station deduplication (within + cross-border) ===");
        Map<String, String> aliasToCanonical = findWithinFeedDuplicates(graph);
        findCrossBorderDuplicates(graph, aliasToCanonical);
        if (!aliasToCanonical.isEmpty()) graph.mergeStations(aliasToCanonical);
        log.info("  Deduplication complete: {} total stations merged", aliasToCanonical.size());
    }

    private Map<String, String> findWithinFeedDuplicates(TransitGraph graph) {
        Map<String, List<Station>> byName = new HashMap<>();
        for (Station station : graph.stations()) {
            if (station.getName() != null) {
                byName.computeIfAbsent(station.getName().trim(), k -> new ArrayList<>()).add(station);
            }
        }

        Map<String, String> aliasToCanonical = new HashMap<>();
        for (List<Station> sameName : byName.values()) {
            if (sameName.size() < 2) continue;
            Map<String, List<Station>> bySource = sameName.stream()
                    .collect(Collectors.groupingBy(s -> StopIds.source(s.getId())));
            for (List<Station> group : bySource.values()) {
                mergeNearbyPairs(graph, group, aliasToCanonical);
            }
        }
        log.info("  Within-country duplicates merged: {}", aliasToCanonical.size());
        return aliasToCanonical;
    }

    private void mergeNearbyPairs(TransitGraph graph, List<Station> group, Map<String, String> aliasToCanonical) {
        for (int i = 0; i < group.size(); i++) {
            for (int j = i + 1; j < group.size(); j++) {
                Station s1 = group.get(i);
                Station s2 = group.get(j);
                if (aliasToCanonical.containsKey(s1.getId()) || aliasToCanonical.containsKey(s2.getId())) continue;
                if (!areNear(s1, s2, WITHIN_FEED_RADIUS_DEG)) continue;
                String canonical = betterConnected(graph, s1, s2);
                aliasToCanonical.put(canonical.equals(s1.getId()) ? s2.getId() : s1.getId(), canonical);
            }
        }
    }

    private void findCrossBorderDuplicates(TransitGraph graph, Map<String, String> aliasToCanonical) {
        Map<String, List<Station>> byCell = bucketByCell(graph, aliasToCanonical);
        Set<String> processedPairs = new HashSet<>();
        int merges = 0;
        for (Map.Entry<String, List<Station>> cell : byCell.entrySet()) {
            for (List<Station> neighbourCell : neighbourCells(byCell, cell.getKey())) {
                for (Station s1 : cell.getValue()) {
                    for (Station s2 : neighbourCell) {
                        if (!processedPairs.add(pairId(s1, s2))) continue;
                        if (tryCrossBorderMerge(graph, s1, s2, aliasToCanonical)) merges++;
                    }
                }
            }
        }
        log.info("  Cross-border stations merged: {}", merges);
    }

    private Map<String, List<Station>> bucketByCell(TransitGraph graph, Map<String, String> aliasToCanonical) {
        Map<String, List<Station>> byCell = new HashMap<>();
        for (Station station : graph.stations()) {
            if (aliasToCanonical.containsKey(station.getId())) continue;
            int cellX = (int) Math.floor(station.getLatitude() / CROSS_BORDER_CELL_SIZE_DEG);
            int cellY = (int) Math.floor(station.getLongitude() / CROSS_BORDER_CELL_SIZE_DEG);
            byCell.computeIfAbsent(cellKey(cellX, cellY), k -> new ArrayList<>()).add(station);
        }
        return byCell;
    }

    /** The 3×3 block of existing cells around (and including) the given one. */
    private List<List<Station>> neighbourCells(Map<String, List<Station>> byCell, String cellKey) {
        String[] parts = cellKey.split(":");
        int baseX = Integer.parseInt(parts[0]);
        int baseY = Integer.parseInt(parts[1]);
        List<List<Station>> cells = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                List<Station> cell = byCell.get(cellKey(baseX + dx, baseY + dy));
                if (cell != null) cells.add(cell);
            }
        }
        return cells;
    }

    private boolean tryCrossBorderMerge(TransitGraph graph, Station s1, Station s2,
                                        Map<String, String> aliasToCanonical) {
        if (aliasToCanonical.containsKey(s1.getId()) || aliasToCanonical.containsKey(s2.getId())) return false;
        if (StopIds.source(s1.getId()).equals(StopIds.source(s2.getId()))) return false;
        if (!areNear(s1, s2, CROSS_BORDER_RADIUS_DEG)) return false;
        double similarity = tokenSimilarity(s1.getName(), s2.getName());
        if (similarity < CROSS_BORDER_MIN_NAME_SIMILARITY) return false;

        String canonical = betterConnected(graph, s1, s2);
        String alias = canonical.equals(s1.getId()) ? s2.getId() : s1.getId();
        aliasToCanonical.put(alias, canonical);
        log.info("  MERGED: '{}' ({}) -> '{}' ({}) [sim={}]",
                alias, s1.getName().equals(alias) ? s1.getName() : s2.getName(),
                canonical, s1.getName().equals(canonical) ? s1.getName() : s2.getName(),
                String.format("%.2f", similarity));
        return true;
    }

    /** ID of the station with more outgoing edges; s1 on a tie. */
    private static String betterConnected(TransitGraph graph, Station s1, Station s2) {
        return graph.getNeighbors(s1.getId()).size() >= graph.getNeighbors(s2.getId()).size()
                ? s1.getId() : s2.getId();
    }

    private static boolean areNear(Station s1, Station s2, double radiusDeg) {
        return Coordinates.areNear(s1.getLatitude(), s1.getLongitude(), s2.getLatitude(), s2.getLongitude(),
                radiusDeg);
    }

    private static String cellKey(int cellX, int cellY) {
        return cellX + ":" + cellY;
    }

    private static String pairId(Station s1, Station s2) {
        return s1.getId().compareTo(s2.getId()) < 0
                ? s1.getId() + "|" + s2.getId()
                : s2.getId() + "|" + s1.getId();
    }

    /** Jaccard similarity of the lower-cased word sets. */
    private static double tokenSimilarity(String name1, String name2) {
        if (name1 == null || name2 == null) return 0.0;
        Set<String> tokens1 = tokenize(name1);
        Set<String> tokens2 = tokenize(name2);
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(tokens1);
        intersection.retainAll(tokens2);
        Set<String> union = new HashSet<>(tokens1);
        union.addAll(tokens2);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenize(String name) {
        return Arrays.stream(name.toLowerCase().split("[\\s\\p{Punct}]+"))
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toSet());
    }
}
