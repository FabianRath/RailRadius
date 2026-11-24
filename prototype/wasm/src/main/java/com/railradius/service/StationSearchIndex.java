package com.railradius.service;

import com.railradius.model.Station;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prototype stand-in (#8) for the backend's station name index: the browser engine searches by station ID and the name
 * search stays on the server, so it builds none. (TeaVM's regex engine also rejects the name variants' patterns.)
 */
public class StationSearchIndex {

    record Index(List<Station> ranked, List<String> names, Map<String, int[]> positionsByGram) {
        static final Index EMPTY = new Index(List.of(), List.of(), Map.of());
    }

    static Index build(Collection<Station> stations, Map<String, Set<String>> translatedNames, TimetableStore store) {
        return Index.EMPTY;
    }
}
