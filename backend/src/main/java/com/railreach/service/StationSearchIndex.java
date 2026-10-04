package com.railreach.service;

import com.railreach.model.Station;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Substring search over station names. A trigram index finds the candidates, so a search does not scan
 * every station. Must be rebuilt after the graph changes ({@link GtfsDataService} does so after each load).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StationSearchIndex {

    private static final int GRAM_LENGTH = 3;

    private final TransitGraph graph;

    private volatile Index index = new Index(List.of(), List.of(), Map.of());

    /**
     * Searchable stations best connected first, their normalized names at the same positions, and per trigram
     * the ascending positions of the names that contain it. Ascending positions keep candidates in rank order.
     */
    private record Index(List<Station> ranked, List<String> names, Map<String, int[]> positionsByGram) {}

    public void rebuild() {
        List<Station> ranked = graph.stations().stream()
                .filter(s -> s.getName() != null && !graph.getNeighbors(s.getId()).isEmpty())
                .sorted(Comparator.comparingInt((Station s) -> graph.getNeighbors(s.getId()).size()).reversed()
                        .thenComparing(Station::getId))
                .toList();
        List<String> names = ranked.stream().map(s -> normalize(s.getName())).toList();

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

    /** Stations whose name contains the query and that have outgoing edges, best connected first. */
    public List<Station> search(String query, int limit) {
        Index current = index;
        String q = normalize(query);
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

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
