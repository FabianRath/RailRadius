package com.railradius.service;

import com.railradius.model.Station;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
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
 * ({@link StationNameVariants}). A trigram index finds the candidates, so a search does not scan every station. The
 * index is part of the {@link ServedGraph}: a load builds it ({@link #build}) along with the graph it indexes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StationSearchIndex {

    public static final int MAX_LIMIT = 50;
    // The longest stop name in the full data has 78 characters, so only made-up queries are turned away
    public static final int MAX_QUERY_LENGTH = 100;
    private static final int GRAM_LENGTH = 3;
    // Folded queries contain no newline, so a match never spans two variants.
    private static final String VARIANT_SEPARATOR = "\n";
    private static final StationNameVariants NAME_VARIANTS = new StationNameVariants();

    private final TransitGraph graph;

    /**
     * Searchable stations best connected first, their joined name variants at the same positions, and per trigram
     * the ascending positions of the names that contain it. Ascending positions keep candidates in rank order.
     */
    record Index(List<Station> ranked, List<String> names, Map<String, int[]> positionsByGram) {
        static final Index EMPTY = new Index(List.of(), List.of(), Map.of());
    }

    /** The index of these stations, by the variants of their names and translated names. */
    static Index build(Collection<Station> stations, Map<String, Set<String>> translatedNames, TimetableStore store) {
        List<Station> ranked = stations.stream()
                .filter(s -> s.getName() != null && store.nextStationCount(s.getId()) > 0)
                .sorted(Comparator.comparingInt((Station s) -> store.nextStationCount(s.getId())).reversed()
                        .thenComparing(Station::getId))
                .toList();
        List<String> names = ranked.stream().map(station -> joinedVariants(station, translatedNames)).toList();

        Map<String, List<Integer>> positions = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            for (String gram : grams(names.get(i))) {
                positions.computeIfAbsent(gram, k -> new ArrayList<>()).add(i);
            }
        }
        Map<String, int[]> positionsByGram = new HashMap<>(positions.size() * 2);
        positions.forEach((gram, list) ->
                positionsByGram.put(gram, list.stream().mapToInt(Integer::intValue).toArray()));

        log.info("Station search index built: {} stations, {} trigrams", ranked.size(), positionsByGram.size());
        return new Index(ranked, names, positionsByGram);
    }

    /** The variants of the station's name and of its translated names, without repeats. */
    private static String joinedVariants(Station station, Map<String, Set<String>> translatedNames) {
        Set<String> variants = new LinkedHashSet<>(NAME_VARIANTS.variants(station.getName()));
        for (String translatedName : translatedNames.getOrDefault(station.getId(), Set.of())) {
            variants.addAll(NAME_VARIANTS.variants(translatedName));
        }
        return String.join(VARIANT_SEPARATOR, variants);
    }

    /**
     * Stations with a name variant that contains the query and that have outgoing edges, best connected first.
     * @throws SearchRefusal if the limit is outside 1–{@value #MAX_LIMIT} or the query is too long (B44)
     */
    public List<Station> search(String query, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw SearchRefusal.invalid("The limit must be between 1 and " + MAX_LIMIT);
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw SearchRefusal.invalid("The query must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        Index current = graph.served().searchIndex();
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
