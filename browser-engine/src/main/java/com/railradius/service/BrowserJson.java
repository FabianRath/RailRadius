package com.railradius.service;

import com.railradius.dto.ReachableStationSummary;

import java.util.List;
import java.util.StringJoiner;

/**
 * What the browser engine hands to JavaScript, as JSON in the server's shapes: the stream's station summaries and a
 * path's rides as the ride-list journey endpoint takes them. TeaVM has no Jackson, so it is written by hand.
 */
final class BrowserJson {

    private static final int FIRST_PRINTABLE = 0x20;
    private static final int HEX_TWO_DIGITS = 0x10;

    private BrowserJson() {
    }

    static String summaries(List<ReachableStationSummary> stations) {
        StringJoiner array = new StringJoiner(",");
        for (ReachableStationSummary station : stations) {
            array.add(object(new StringJoiner(",")
                    .add(field("stopId", text(station.getStopId())))
                    .add(field("stopName", text(station.getStopName())))
                    .add(field("country", text(station.getCountry())))
                    .add(field("latitude", String.valueOf(station.getLatitude())))
                    .add(field("longitude", String.valueOf(station.getLongitude())))
                    .add(field("transfers", String.valueOf(station.getTransfers())))
                    .add(field("totalDuration", text(station.getTotalDuration())))
                    .add(field("totalDurationMinutes", String.valueOf(station.getTotalDurationMinutes())))));
        }
        return array(array);
    }

    static String rides(List<CompactResult.Ride> rides) {
        StringJoiner array = new StringJoiner(",");
        for (CompactResult.Ride ride : rides) {
            array.add(object(new StringJoiner(",")
                    .add(field("trip", String.valueOf(ride.trip())))
                    .add(field("boardEvent", String.valueOf(ride.boardEvent())))
                    .add(field("alightEvent", String.valueOf(ride.alightEvent())))));
        }
        return array(array);
    }

    // The stand-in StringJoiner takes no prefix and suffix
    private static String array(StringJoiner elements) {
        return "[" + elements + "]";
    }

    private static String object(StringJoiner fields) {
        return "{" + fields + "}";
    }

    private static String field(String name, String json) {
        return "\"" + name + "\":" + json;
    }

    // String.valueOf writes null as null, which is JSON too
    private static String text(String value) {
        if (value == null) return "null";
        StringBuilder json = new StringBuilder(value.length() + 2).append('"');
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
        return json.append('"').toString();
    }
}
