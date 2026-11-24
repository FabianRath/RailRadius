package com.railradius.service;

import com.railradius.util.ResourceLines;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * Prototype only (#8): times the backend's own search (real threads) on the same starts and runs as
 * web/dev-check.mjs. Args: store file, bench set, today, optional start limit; -Druns=n. Run from backend/ with backend classes on the classpath.
 */
public final class JvmBench {

    private static final int RUNS = Integer.getInteger("runs", 5);

    public static void main(String[] args) throws Exception {
        TransitGraph graph = TransitGraph.withToday(LocalDate.parse(args[2]));
        graph.serve(new BinaryCacheStore(args[0]).load().orElseThrow());
        CompactReachabilitySearch search = new CompactReachabilitySearch(graph);
        List<String> starts = SearchSnapshot.benchStarts(ResourceLines.read(Path.of("../docs/bench-stations.txt")),
                args[1]).stream().limit(args.length > 3 ? Integer.parseInt(args[3]) : Long.MAX_VALUE).toList();
        for (String start : starts) {
            for (int transfers : new int[] {3, 5}) {
                search.search(start, transfers, 0, 180, CompactReachabilitySearch.NO_DEADLINE, paths -> {});
                long[] times = new long[RUNS];
                int[] stations = {0};
                for (int i = 0; i < RUNS; i++) {
                    long startNanos = System.nanoTime();
                    CompactResult result = search.search(start, transfers, 0, 180,
                            CompactReachabilitySearch.NO_DEADLINE, paths -> {});
                    times[i] = (System.nanoTime() - startNanos) / 1_000_000;
                    stations[0] = 0;
                    result.forEachStation((id, paths) -> stations[0]++);
                }
                Arrays.sort(times);
                System.out.printf("%s x%d: %d stations, best %d ms, median %d ms%n", start, transfers, stations[0],
                        times[0], times[RUNS / 2]);
            }
        }
    }
}
