package com.railreach.util;

/** Canonical stop IDs are {@code <source>:<rawId>}, where source comes from the feed's {@code gtfs_<source>.zip}. */
public final class StopIds {

    private static final char SEPARATOR = ':';

    private StopIds() {}

    public static String of(String source, String rawId) {
        return source + SEPARATOR + rawId;
    }

    /** The feed source of a canonical ID, e.g. {@code germany} for {@code germany:8011160}. */
    public static String source(String stopId) {
        int separatorIdx = stopId.indexOf(SEPARATOR);
        return separatorIdx < 0 ? stopId : stopId.substring(0, separatorIdx);
    }
}
