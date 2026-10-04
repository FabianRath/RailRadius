package com.railreach.service;

import com.railreach.model.Station;
import com.railreach.util.Coordinates;
import com.railreach.util.StopIds;
import lombok.RequiredArgsConstructor;
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
 * Merges stations that are the same place after all feeds are loaded. The rules run in this order, each on the
 * stations the earlier ones left:
 * <ol>
 *   <li>exact-name duplicates within one feed;</li>
 *   <li>stations of different feeds near each other with similar names;</li>
 *   <li>stations of different feeds near each other that share trains ({@link SharedTrainMatcher}): needs rule 2
 *       first, as it compares neighbouring stations;</li>
 *   <li>a feed's stops abroad folded into the local feed's station ({@link ForeignStopSnapper}).</li>
 * </ol>
 * In rules 1 to 3 the better connected station survives; in rule 4 the local one.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StationDeduplicator {

    // ~200 m
    private static final double WITHIN_FEED_RADIUS_DEG = 0.002;
    // ~500 m
    private static final double CROSS_BORDER_RADIUS_DEG = 0.005;
    // Cross-border candidates are bucketed into ~1 km cells; only neighbouring cells are compared
    private static final double CROSS_BORDER_CELL_SIZE_DEG = 0.01;
    // 0.4 still accepts "Paris Gare du Nord" vs "Paris Nord (FR)"
    private static final double CROSS_BORDER_MIN_NAME_SIMILARITY = 0.4;

    private final CountryLocator countryLocator;

    void deduplicate(TransitGraph graph) {
        log.info("=== Station deduplication ===");
        assignCountries(graph);
        Map<String, String> aliasToCanonical = findWithinFeedDuplicates(graph);
        List<StationPair> candidates = crossBorderCandidates(graph);
        mergeSimilarNames(graph, candidates, aliasToCanonical);
        mergeSharedTrains(graph, candidates, aliasToCanonical);
        int snapped = ForeignStopSnapper.snap(graph.stations(), aliasToCanonical);
        log.info("  Foreign stops merged into the local station: {}", snapped);
        if (!aliasToCanonical.isEmpty()) graph.mergeStations(aliasToCanonical);
        log.info("  Deduplication complete: {} total stations merged", aliasToCanonical.size());
    }

    /** Merged stations keep the canonical station's country, so this runs once before any merge. */
    private void assignCountries(TransitGraph graph) {
        for (Station station : graph.stations()) {
            station.setCountry(countryLocator.countryAt(station.getLatitude(), station.getLongitude()).orElse(null));
        }
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
                Station canonical = betterConnected(graph, s1, s2);
                aliasToCanonical.put((canonical == s1 ? s2 : s1).getId(), canonical.getId());
            }
        }
    }

    /** Pairs of stations of different feeds within the cross-border radius, each pair once. */
    private static List<StationPair> crossBorderCandidates(TransitGraph graph) {
        StationGrid grid = new StationGrid(graph.stations(), CROSS_BORDER_CELL_SIZE_DEG);
        List<StationPair> pairs = new ArrayList<>();
        for (Station s1 : graph.stations()) {
            for (Station s2 : grid.near(s1)) {
                if (s1.getId().compareTo(s2.getId()) >= 0) continue;
                if (StopIds.source(s1.getId()).equals(StopIds.source(s2.getId()))) continue;
                if (areNear(s1, s2, CROSS_BORDER_RADIUS_DEG)) pairs.add(new StationPair(s1, s2));
            }
        }
        return pairs;
    }

    private void mergeSimilarNames(TransitGraph graph, List<StationPair> candidates,
                                  Map<String, String> aliasToCanonical) {
        int merges = 0;
        for (StationPair pair : candidates) {
            if (pair.eitherMerged(aliasToCanonical)) continue;
            double similarity = tokenSimilarity(pair.s1().getName(), pair.s2().getName());
            if (similarity < CROSS_BORDER_MIN_NAME_SIMILARITY) continue;
            mergeIntoBetterConnected(graph, pair, aliasToCanonical, String.format("sim=%.2f", similarity));
            merges++;
        }
        log.info("  Cross-border stations merged by name: {}", merges);
    }

    /** Repeats until nothing changes: each merge can make neighbours of a later pair count as one station. */
    private void mergeSharedTrains(TransitGraph graph, List<StationPair> candidates,
                                  Map<String, String> aliasToCanonical) {
        int merges = 0;
        int roundMerges;
        do {
            roundMerges = 0;
            for (StationPair pair : candidates) {
                if (pair.eitherMerged(aliasToCanonical)) continue;
                if (!SharedTrainMatcher.shareTrains(graph, pair.s1().getId(), pair.s2().getId(), aliasToCanonical)) {
                    continue;
                }
                mergeIntoBetterConnected(graph, pair, aliasToCanonical, "shared trains");
                roundMerges++;
            }
            merges += roundMerges;
        } while (roundMerges > 0);
        log.info("  Cross-border stations merged by shared trains: {}", merges);
    }

    private static void mergeIntoBetterConnected(TransitGraph graph, StationPair pair,
                                                 Map<String, String> aliasToCanonical, String reason) {
        Station canonical = betterConnected(graph, pair.s1(), pair.s2());
        Station alias = canonical == pair.s1() ? pair.s2() : pair.s1();
        aliasToCanonical.put(alias.getId(), canonical.getId());
        log.info("  MERGED: '{}' ({}) -> '{}' ({}) [{}]",
                alias.getId(), alias.getName(), canonical.getId(), canonical.getName(), reason);
    }

    /** The station with more outgoing edges; s1 on a tie. */
    private static Station betterConnected(TransitGraph graph, Station s1, Station s2) {
        return graph.getNeighbors(s1.getId()).size() >= graph.getNeighbors(s2.getId()).size() ? s1 : s2;
    }

    private static boolean areNear(Station s1, Station s2, double radiusDeg) {
        return Coordinates.areNear(s1.getLatitude(), s1.getLongitude(), s2.getLatitude(), s2.getLongitude(),
                radiusDeg);
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

    private record StationPair(Station s1, Station s2) {

        boolean eitherMerged(Map<String, String> aliasToCanonical) {
            return aliasToCanonical.containsKey(s1.getId()) || aliasToCanonical.containsKey(s2.getId());
        }
    }
}
