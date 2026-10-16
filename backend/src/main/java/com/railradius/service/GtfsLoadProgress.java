package com.railradius.service;

import com.railradius.dto.GtfsLoadStatus;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Arrays;

/**
 * What a running GTFS load from the zips is doing, for the admin panel's progress bar. One thread loads and writes,
 * status requests read; a reader may see fields from two neighbouring updates, which is fine for a progress display.
 * Parse progress is measured in zip bytes because feeds differ in size by orders of magnitude.
 */
@Component
public class GtfsLoadProgress {

    public enum Phase { IDLE, PARSING, MERGING, SAVING, DONE, FAILED }

    private static final double PERCENT = 100.0;
    private static final long MS_PER_SECOND = 1000;

    private volatile Phase phase = Phase.IDLE;
    private volatile long startedAtMs;
    private volatile long finishedAtMs;
    private volatile int feedsTotal;
    private volatile int feedsDone;
    private volatile long bytesTotal;
    private volatile long bytesDone;
    private volatile String currentFeed;

    void start() {
        feedsTotal = 0;
        feedsDone = 0;
        bytesTotal = 0;
        bytesDone = 0;
        currentFeed = null;
        finishedAtMs = 0;
        startedAtMs = System.currentTimeMillis();
        phase = Phase.PARSING;
    }

    void feedsFound(File[] zipFiles) {
        feedsTotal = zipFiles.length;
        bytesTotal = Arrays.stream(zipFiles).mapToLong(File::length).sum();
    }

    void feedStarted(File zipFile) {
        currentFeed = zipFile.getName();
    }

    void feedFinished(File zipFile) {
        feedsDone++;
        bytesDone += zipFile.length();
    }

    void enter(Phase next) {
        phase = next;
        currentFeed = null;
        if (next == Phase.DONE || next == Phase.FAILED) finishedAtMs = System.currentTimeMillis();
    }

    /** True while a load from the zips reads or writes them. */
    boolean isRunning() {
        Phase current = phase;
        return current == Phase.PARSING || current == Phase.MERGING || current == Phase.SAVING;
    }

    GtfsLoadStatus status(int stationCount, int connectionCount) {
        Phase current = phase;
        long endMs = finishedAtMs != 0 ? finishedAtMs : System.currentTimeMillis();
        long elapsedMs = current == Phase.IDLE ? 0 : endMs - startedAtMs;
        double parsedFraction = bytesTotal > 0 ? (double) bytesDone / bytesTotal : 0;
        return GtfsLoadStatus.builder()
                .phase(current.name())
                .feedsDone(feedsDone)
                .feedsTotal(feedsTotal)
                .currentFeed(currentFeed)
                .parsedPercent(parsedFraction * PERCENT)
                .elapsedSeconds(elapsedMs / MS_PER_SECOND)
                .parseEtaSeconds(parseEtaSeconds(current, elapsedMs, parsedFraction))
                .stationCount(stationCount)
                .connectionCount(connectionCount)
                .build();
    }

    /** Extrapolated from the bytes parsed so far; unknown before the first feed is done and after parsing. */
    private static Long parseEtaSeconds(Phase current, long elapsedMs, double parsedFraction) {
        if (current != Phase.PARSING || parsedFraction == 0) return null;
        return Math.round(elapsedMs * (1 - parsedFraction) / parsedFraction / MS_PER_SECOND);
    }
}
