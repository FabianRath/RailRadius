package com.railradius.service;

import com.railradius.dto.Coverage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The countries the loaded data covers: each feed's home country. A cross-border train's last stops abroad don't
 * count, as only those few stations of that country are there.
 */
@Service
@RequiredArgsConstructor
public class CoverageService {

    private final TransitGraph transitGraph;

    public Coverage coverage() {
        return Coverage.builder()
                .countries(HomeCountries.bySource(transitGraph.getAllStations().values()).values().stream()
                        .distinct()
                        .sorted()
                        .toList())
                .build();
    }
}
