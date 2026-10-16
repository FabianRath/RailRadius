package com.railradius.service;

import com.railradius.model.Station;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Substring search over station names, their translations from the feeds and the variants of both
 * ({@link StationNameVariants}). A trigram index finds the candidates, so a search does not scan every station.
 * Must be rebuilt after the graph changes ({@link GtfsDataService} does so after each load).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StationSearchIndex {

    private static final int GRAM_LENGTH = 3;
    // Folded queries contain no newline, so a match never spans two variants.
    private static final String VARIANT_SEPARATOR = "\n";

    private final TransitGraph graph;
    private final StationNameVariants nameVariants = new StationNameVariants();

    private volatile Index index = new Index(List.of(), List.of(), Map.of());

    /**
     * Searchable stations best connected first, their joined name variants at the same positions, and per trigram
     * the ascending positions of the names that contain it. Ascending positions keep candidates in rank order.
     */
    private record Index(List<Station> ranked, List<String> names, Map<String, int[]> positionsByGram) {}

    public void rebuild() {
        List<Station> ranked = graph.stations().stream()
                .filter(s -> s.getName() != null && graph.nextStationCount(s.getId()) > 0)
                .sorted(Comparator.comparingInt((Station s) -> graph.nextStationCount(s.getId())).reversed()
                        .thenComparing(Station::getId))
                .toList();
        List<String> names = ranked.stream().map(this::joinedVariants).toList();

        Map<String, List<Integer>> positions = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            for (String gram : grams(names.get(i))) {
                positions.computeIfAbsent(gram, k -> new ArrayList<>()).add(i);
            }
        }
        Map<String, int[]> positionsByGram = new HashMap<>(positions.size() * 2);
        positions.forEach((gram, list) ->
                positionsByGram.put(gram, list.stream().mapToInt(Integer::intValue).toArray()));

        index = new Index(ranked, names, positionsByGram);
        log.info("Station search index built: {} stations, {} trigrams", ranked.size(), positionsByGram.size());
    }

    /** The variants of the station's name and of its translated names, without repeats. */
    private String joinedVariants(Station station) {
        Set<String> variants = new LinkedHashSet<>(nameVariants.variants(station.getName()));
        for (String translatedName : graph.getTranslatedNames(station.getId())) {
            variants.addAll(nameVariants.variants(translatedName));
        }
        return String.join(VARIANT_SEPARATOR, variants);
    }

    /** Stations with a name variant that contains the query and that have outgoing edges, best connected first. */
    public List<Station> search(String query, int limit) {
        Index current = index;
        String q = StationNameVariants.fold(query);
        return candidates(current, q)
                .filter(position -> current.names().get(position).contains(q))
                .limit(limit)
                .mapToObj(current.ranked()::get)
                .toList();
    }

    /** Every name containing the query contains each of its trigrams, so the rarest trigram's list suffices. */
    private static IntStream candidates(Index index, String query) {
        if (query.length() < GRAM_LENGTH) return IntStream.range(0, index.ranked().size());
        int[] rarest = null;
        for (String gram : grams(query)) {
            int[] positions = index.positionsByGram().get(gram);
            if (positions == null) return IntStream.empty();
            if (rarest == null || positions.length < rarest.length) rarest = positions;
        }
        return IntStream.of(rarest);
    }

    private static Set<String> grams(String text) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + GRAM_LENGTH <= text.length(); i++) {
            grams.add(text.substring(i, i + GRAM_LENGTH));
        }
        return grams;
    }
}
