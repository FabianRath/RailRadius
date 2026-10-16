package com.railradius.service;

import com.railradius.model.Station;
import com.railradius.util.StopIds;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** A feed's home country is where most of its stations lie; stops abroad are a cross-border train's last stops. */
final class HomeCountries {

    private static final double HOME_COUNTRY_MIN_SHARE = 0.5;

    private HomeCountries() {}

    /** Source → the country most of its located stations lie in, for sources where that is more than half. */
    static Map<String, String> bySource(Collection<Station> stations) {
        Map<String, Map<String, Long>> countsBySource = stations.stream()
                .filter(station -> station.getCountry() != null)
                .collect(Collectors.groupingBy(station -> StopIds.source(station.getId()),
                        Collectors.groupingBy(Station::getCountry, Collectors.counting())));
        Map<String, String> homeBySource = new HashMap<>();
        countsBySource.forEach((source, counts) -> {
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .filter(top -> top.getValue() > total * HOME_COUNTRY_MIN_SHARE)
                    .ifPresent(top -> homeBySource.put(source, top.getKey()));
        });
        return homeBySource;
    }
}
