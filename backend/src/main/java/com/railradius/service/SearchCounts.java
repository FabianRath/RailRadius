package com.railradius.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Searches per day by where they ran, for #7's success measure: on the server (each stream, stored results included)
 * or in a browser (each one it reports, #12). In memory only, so a restart starts the count over.
 */
@Service
@RequiredArgsConstructor
public class SearchCounts {

    private final TransitGraph graph;
    private final ConcurrentSkipListMap<Integer, DayCounts> byEpochDay = new ConcurrentSkipListMap<>();

    /** One day's searches: date as yyyy-mm-dd. */
    public record DaySearches(String date, long server, long browser) {}

    private record DayCounts(LongAdder server, LongAdder browser) {
        DayCounts() {
            this(new LongAdder(), new LongAdder());
        }
    }

    public void countServerSearch() {
        today().server().increment();
    }

    public void countBrowserSearch() {
        today().browser().increment();
    }

    /** Every day since the start that had a search, oldest first. */
    public List<DaySearches> perDay() {
        return byEpochDay.entrySet().stream()
                .map(day -> new DaySearches(LocalDate.ofEpochDay(day.getKey()).toString(),
                        day.getValue().server().sum(), day.getValue().browser().sum()))
                .toList();
    }

    private DayCounts today() {
        return byEpochDay.computeIfAbsent(graph.todayEpochDay(), day -> new DayCounts());
    }
}
