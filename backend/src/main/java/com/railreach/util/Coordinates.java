package com.railreach.util;

public final class Coordinates {

    private Coordinates() {}

    /** Cheap proximity check: both the latitude and the longitude differ by less than {@code thresholdDeg}. */
    public static boolean areNear(double lat1, double lon1, double lat2, double lon2, double thresholdDeg) {
        return Math.abs(lat1 - lat2) < thresholdDeg && Math.abs(lon1 - lon2) < thresholdDeg;
    }
}
