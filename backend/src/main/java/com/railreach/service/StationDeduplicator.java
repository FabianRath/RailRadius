package com.railreach.service;

import com.railreach.model.Station;
import com.railreach.util.Coordinates;
import com.railreach.util.StopIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Merges stations that are the same place after all feeds are loaded. The rules run in this order, each on the
 * stations the earlier ones left:
 * <ol>
 *   <li>exact-name duplicates within one feed;</li>
 *   <li>stations of different feeds with the same station code ({@link StationCodes}) up to a few km apart, as one
 *       feed's coordinates can be that far off;</li>
 *   <li>stations of different feeds near each other with similar names ({@link StationNameMatcher}), or very near
 *       each other where one name has all the other's words;</li>
 *   <li>stations of different feeds near each other that share trains ({@link SharedTrainMatcher}): needs rules 2
 *       and 3 first, as it compares neighbouring stations;</li>
 *   <li>a feed's stops abroad folded into the local feed's station ({@link ForeignStopSnapper}).</li>
 * </ol>
 * In rules 1 to 4 the better connected station survives; in rule 5 the local one.
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
    // ~100 m. "Split" and "Split željeznički kolodvor" are one station, but Cotral's "Viterbo" and Trenitalia's
    // "Viterbo Porta Fiorentina", 126 m apart, are two
    private static final double CONTAINED_NAME_RADIUS_DEG = 0.001;
    // The farthest true match on the full data is 3.8 km (Cavaria, Trenord / Swiss feed); a false one, the
    // Austrian numbers that repeat per state, is 160 km and more
    private static final double SAME_CODE_MAX_DISTANCE_METERS = 5000;

    private final CountryLocator countryLocator;
    private final StationNameMatcher nameMatcher = new StationNameMatcher();
    private final StationCodes stationCodes = new StationCodes();

    void deduplicate(RailNetwork network) {
        log.info("=== Station deduplication ===");
        assignCountries(network);
        Map<String, String> aliasToCanonical = findWithinFeedDuplicates(network);
        mergeSameStationCodes(network, aliasToCanonical);
        List<StationPair> candidates = crossBorderCandidates(network);
        mergeSimilarNames(network, candidates, aliasToCanonical);
        mergeSharedTrains(network, candidates, aliasToCanonical);
        int snapped = ForeignStopSnapper.snap(network.stations(), nameMatcher, aliasToCanonical);
        log.info("  Foreign stops merged into the local station: {}", snapped);
        if (!aliasToCanonical.isEmpty()) network.mergeStations(aliasToCanonical);
        log.info("  Deduplication complete: {} total stations merged", aliasToCanonical.size());
    }

    /** Merged stations keep the canonical station's country, so this runs once before any merge. */
    private void assignCountries(RailNetwork network) {
        for (Station station : network.stations()) {
            station.setCountry(countryLocator.countryAt(station.getLatitude(), station.getLongitude()).orElse(null));
        }
    }

    private Map<String, String> findWithinFeedDuplicates(RailNetwork network) {
        Map<String, List<Station>> byName = new HashMap<>();
        for (Station station : network.stations()) {
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
                mergeNearbyPairs(network, group, aliasToCanonical);
            }
        }
        log.info("  Within-country duplicates merged: {}", aliasToCanonical.size());
        return aliasToCanonical;
    }

    private void mergeNearbyPairs(RailNetwork network, List<Station> group, Map<String, String> aliasToCanonical) {
        for (int i = 0; i < group.size(); i++) {
            for (int j = i + 1; j < group.size(); j++) {
                Station s1 = group.get(i);
                Station s2 = group.get(j);
                if (aliasToCanonical.containsKey(s1.getId()) || aliasToCanonical.containsKey(s2.getId())) continue;
                if (!areNear(s1, s2, WITHIN_FEED_RADIUS_DEG)) continue;
                Station canonical = betterConnected(network, s1, s2);
                aliasToCanonical.put((canonical == s1 ? s2 : s1).getId(), canonical.getId());
            }
        }
    }

    private void mergeSameStationCodes(RailNetwork network, Map<String, String> aliasToCanonical) {
        Map<String, List<Station>> byCode = new HashMap<>();
        for (Station station : network.stations()) {
            if (aliasToCanonical.containsKey(station.getId())) continue;
            stationCodes.of(station.getId())
                    .ifPresent(code -> byCode.computeIfAbsent(code, k -> new ArrayList<>()).add(station));
        }
        int merges = 0;
        for (Map.Entry<String, List<Station>> sameCode : byCode.entrySet()) {
            merges += mergeSameCodePairs(network, sameCode.getKey(), sameCode.getValue(), aliasToCanonical);
        }
        log.info("  Cross-border stations merged by station code: {}", merges);
    }

    /** Returns the number of merges. */
    private static int mergeSameCodePairs(RailNetwork network, String code, List<Station> sameCode,
                                          Map<String, String> aliasToCanonical) {
        int merges = 0;
        for (int i = 0; i < sameCode.size(); i++) {
            for (int j = i + 1; j < sameCode.size(); j++) {
                StationPair pair = new StationPair(sameCode.get(i), sameCode.get(j));
                if (pair.eitherMerged(aliasToCanonical) || pair.sameSource()) continue;
                if (pair.distanceMeters() > SAME_CODE_MAX_DISTANCE_METERS) continue;
                mergeIntoBetterConnected(network, pair, aliasToCanonical, "station code " + code);
                merges++;
            }
        }
        return merges;
    }

    /** Pairs of stations of different feeds within the cross-border radius, each pair once. */
    private static List<StationPair> crossBorderCandidates(RailNetwork network) {
        StationGrid grid = new StationGrid(network.stations(), CROSS_BORDER_CELL_SIZE_DEG);
        List<StationPair> pairs = new ArrayList<>();
        for (Station s1 : network.stations()) {
            for (Station s2 : grid.near(s1)) {
                if (s1.getId().compareTo(s2.getId()) >= 0) continue;
                if (StopIds.source(s1.getId()).equals(StopIds.source(s2.getId()))) continue;
                if (areNear(s1, s2, CROSS_BORDER_RADIUS_DEG)) pairs.add(new StationPair(s1, s2));
            }
        }
        return pairs;
    }

    private void mergeSimilarNames(RailNetwork network, List<StationPair> candidates,
                                  Map<String, String> aliasToCanonical) {
        int merges = 0;
        for (StationPair pair : candidates) {
            if (pair.eitherMerged(aliasToCanonical)) continue;
            String name1 = pair.s1().getName();
            String name2 = pair.s2().getName();
            double similarity = nameMatcher.similarity(name1, name2);
            if (similarity >= CROSS_BORDER_MIN_NAME_SIMILARITY) {
                mergeIntoBetterConnected(network, pair, aliasToCanonical, String.format("sim=%.2f", similarity));
                merges++;
            } else if (areNear(pair.s1(), pair.s2(), CONTAINED_NAME_RADIUS_DEG)
                    && nameMatcher.oneContainsTheOther(name1, name2)) {
                mergeIntoBetterConnected(network, pair, aliasToCanonical, "name contained");
                merges++;
            }
        }
        log.info("  Cross-border stations merged by name: {}", merges);
    }

    /** Repeats until nothing changes: each merge can make neighbours of a later pair count as one station. */
    private void mergeSharedTrains(RailNetwork network, List<StationPair> candidates,
                                  Map<String, String> aliasToCanonical) {
        int merges = 0;
        int roundMerges;
        do {
            roundMerges = 0;
            for (StationPair pair : candidates) {
                if (pair.eitherMerged(aliasToCanonical)) continue;
                if (!SharedTrainMatcher.shareTrains(network, pair.s1().getId(), pair.s2().getId(),
                        aliasToCanonical)) {
                    continue;
                }
                mergeIntoBetterConnected(network, pair, aliasToCanonical, "shared trains");
                roundMerges++;
            }
            merges += roundMerges;
        } while (roundMerges > 0);
        log.info("  Cross-border stations merged by shared trains: {}", merges);
    }

    private static void mergeIntoBetterConnected(RailNetwork network, StationPair pair,
                                                 Map<String, String> aliasToCanonical, String reason) {
        Station canonical = betterConnected(network, pair.s1(), pair.s2());
        Station alias = canonical == pair.s1() ? pair.s2() : pair.s1();
        aliasToCanonical.put(alias.getId(), canonical.getId());
        log.info("  MERGED: '{}' ({}) -> '{}' ({}) [{}]",
                alias.getId(), alias.getName(), canonical.getId(), canonical.getName(), reason);
    }

    /** The station with more outgoing edges; s1 on a tie. */
    private static Station betterConnected(RailNetwork network, Station s1, Station s2) {
        return network.getNeighbors(s1.getId()).size() >= network.getNeighbors(s2.getId()).size() ? s1 : s2;
    }

    private static boolean areNear(Station s1, Station s2, double radiusDeg) {
        return Coordinates.areNear(s1.getLatitude(), s1.getLongitude(), s2.getLatitude(), s2.getLongitude(),
                radiusDeg);
    }

    private record StationPair(Station s1, Station s2) {

        boolean eitherMerged(Map<String, String> aliasToCanonical) {
            return aliasToCanonical.containsKey(s1.getId()) || aliasToCanonical.containsKey(s2.getId());
        }

        boolean sameSource() {
            return StopIds.source(s1.getId()).equals(StopIds.source(s2.getId()));
        }

        double distanceMeters() {
            return Coordinates.distanceMeters(s1.getLatitude(), s1.getLongitude(), s2.getLatitude(), s2.getLongitude());
        }
    }
}
