package com.railradius.service;

import com.railradius.dto.ReachableStationSummary;
import com.railradius.dto.StationLabel;

import java.util.List;

/**
 * What the browser engine hands to JavaScript, as JSON in the server's shapes: the stream's station summaries and a
 * path's rides as the ride-list journey endpoint takes them, and the stations the map names (#96). TeaVM has no Jackson, so it is written by hand, into one
 * builder: many small concatenated strings were most of the step's time in WebAssembly (#83).
 */
final class BrowserJson {

    private static final int FIRST_PRINTABLE = 0x20;
    private static final int HEX_TWO_DIGITS = 0x10;
    // A bit more than a station summary's length (about 200), so the builder rarely grows
    private static final int SUMMARY_CHARS = 240;
    private static final int RIDE_CHARS = 60;
    private static final int LABEL_CHARS = 80;

    private BrowserJson() {
    }

    // Boxed values that are null are written as null (append(Object)), which is JSON too
    static String summaries(List<ReachableStationSummary> stations) {
        StringBuilder json = new StringBuilder(stations.size() * SUMMARY_CHARS).append('[');
        for (ReachableStationSummary station : stations) {
            if (json.length() > 1) json.append(',');
            text(json.append("{\"stopId\":"), station.getStopId());
            text(json.append(",\"stopName\":"), station.getStopName());
            text(json.append(",\"country\":"), station.getCountry());
            json.append(",\"latitude\":").append(station.getLatitude())
                    .append(",\"longitude\":").append(station.getLongitude())
                    .append(",\"transfers\":").append(station.getTransfers());
            text(json.append(",\"totalDuration\":"), station.getTotalDuration());
            json.append(",\"totalDurationMinutes\":").append(station.getTotalDurationMinutes()).append('}');
        }
        return json.append(']').toString();
    }

    static String rides(List<CompactResult.Ride> rides) {
        StringBuilder json = new StringBuilder(rides.size() * RIDE_CHARS).append('[');
        for (CompactResult.Ride ride : rides) {
            if (json.length() > 1) json.append(',');
            json.append("{\"trip\":").append(ride.trip())
                    .append(",\"boardEvent\":").append(ride.boardEvent())
                    .append(",\"alightEvent\":").append(ride.alightEvent()).append('}');
        }
        return json.append(']').toString();
    }

    static String stationLabels(List<StationLabel> labels) {
        StringBuilder json = new StringBuilder(labels.size() * LABEL_CHARS).append('[');
        for (StationLabel label : labels) {
            if (json.length() > 1) json.append(',');
            text(json.append("{\"name\":"), label.getName());
            json.append(",\"latitude\":").append(label.getLatitude())
                    .append(",\"longitude\":").append(label.getLongitude())
                    .append(",\"importance\":").append(label.getImportance()).append('}');
        }
        return json.append(']').toString();
    }

    private static void text(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
            return;
        }
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                json.append('\\').append(c);
            } else if (c < FIRST_PRINTABLE) {
                json.append(c < HEX_TWO_DIGITS ? "\\u000" : "\\u00").append(Integer.toHexString(c));
            } else {
                json.append(c);
            }
        }
        json.append('"');
    }
}
