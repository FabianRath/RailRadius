package com.railreach.service;

import com.railreach.model.Station;
import com.railreach.util.Coordinates;
import com.railreach.util.StopIds;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Merges a feed's stops abroad into the local feed's station. A feed's home country is where most of its stations
 * lie. A stop in another country (a cross-border train's last stops) is a station that country's own feed has too,
 * under whatever name, so it is folded into the nearest station of a feed at home there, if one is close enough.
 */
@Slf4j
final class ForeignStopSnapper {

    // Covers the gap between a foreign feed's stop and the local station (platform vs. building, rough coordinates)
    static final double MAX_SNAP_DISTANCE_METERS = 1000;
    // 0.03° of longitude is still ~1.1 km at 70°N, so the 3×3 cell block covers the snap distance everywhere
    private static final double SNAP_CELL_SIZE_DEG = 0.03;
    private static final double HOME_COUNTRY_MIN_SHARE = 0.5;

    private ForeignStopSnapper() {}

    /** Adds alias → local station for every foreign stop with a local station in reach; returns how many. */
    static int snap(Collection<Station> stations, Map<String, String> aliasToCanonical) {
        Map<String, String> homeBySource = homeCountries(stations);
        StationGrid grid = new StationGrid(stations, SNAP_CELL_SIZE_DEG);

        int snapped = 0;
        for (Station stop : stations) {
            String country = stop.getCountry();
            String home = homeBySource.get(StopIds.source(stop.getId()));
            if (country == null || home == null || country.equals(home)) continue;
            if (aliasToCanonical.containsKey(stop.getId())) continue;
            Optional<Station> local = nearestLocalStation(stop, country, grid, homeBySource, aliasToCanonical);
            if (local.isEmpty()) continue;
            aliasToCanonical.put(stop.getId(), local.get().getId());
            snapped++;
            log.info("  SNAPPED: '{}' ({}) -> '{}' ({})",
                    stop.getId(), stop.getName(), local.get().getId(), local.get().getName());
        }
        return snapped;
    }

    /** Source → the country most of its located stations lie in, for sources where that is more than half. */
    private static Map<String, String> homeCountries(Collection<Station> stations) {
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

    private static Optional<Station> nearestLocalStation(Station stop, String country, StationGrid grid,
                                                         Map<String, String> homeBySource,
                                                         Map<String, String> aliasToCanonical) {
        Function<Station, Double> distanceMeters = candidate -> Coordinates.distanceMeters(
                stop.getLatitude(), stop.getLongitude(), candidate.getLatitude(), candidate.getLongitude());
        return grid.near(stop).stream()
                .filter(candidate -> country.equals(homeBySource.get(StopIds.source(candidate.getId()))))
                .filter(candidate -> !aliasToCanonical.containsKey(candidate.getId()))
                .filter(candidate -> distanceMeters.apply(candidate) <= MAX_SNAP_DISTANCE_METERS)
                .min(Comparator.comparing(distanceMeters));
    }
}
