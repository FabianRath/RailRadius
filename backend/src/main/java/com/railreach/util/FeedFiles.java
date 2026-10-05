package com.railreach.util;

/** A feed's zip is {@code gtfs_<source>.zip}; the source names its stop IDs (see {@link StopIds}). */
public final class FeedFiles {

    private FeedFiles() {}

    public static boolean isZip(String fileName) {
        return fileName.toLowerCase().endsWith(".zip");
    }

    /** {@code gtfs_germany.zip} -> {@code germany}; a zip without the prefix is its own source. */
    public static String source(String zipFileName) {
        return zipFileName.replaceFirst("(?i)\\.zip$", "").replaceFirst("(?i)^gtfs_", "");
    }

    public static String zipName(String source) {
        return "gtfs_" + source + ".zip";
    }
}
