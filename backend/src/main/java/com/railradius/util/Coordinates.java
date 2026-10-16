package com.railradius.util;

public final class Coordinates {

    private static final double METERS_PER_DEGREE_LATITUDE = 111_320;

    private Coordinates() {}

    /** Ground distance, flat-earth approximation: exact enough within a few km. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLatMeters = (lat1 - lat2) * METERS_PER_DEGREE_LATITUDE;
        double dLonMeters = (lon1 - lon2) * METERS_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.hypot(dLatMeters, dLonMeters);
    }

    /** Cheap proximity check: both the latitude and the longitude differ by less than {@code thresholdDeg}. */
    public static boolean areNear(double lat1, double lon1, double lat2, double lon2, double thresholdDeg) {
        return Math.abs(lat1 - lat2) < thresholdDeg && Math.abs(lon1 - lon2) < thresholdDeg;
    }
}
