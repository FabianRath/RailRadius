package com.railradius.service;

import com.railradius.dto.StationLabel;

import java.util.List;

/** The stations the map names, built the same way by the browser engine and the server's fallback (#96, #108). */
public final class StationLabels {

    private StationLabels() {
    }

    /** Every station with coordinates; its importance is its number of edges, so hubs win where names collide. */
    public static List<StationLabel> of(TransitGraph graph) {
        return graph.getAllStations().values().stream()
                .filter(station -> station.getLatitude() != null && station.getLongitude() != null)
                .map(station -> new StationLabel(station.getName(), station.getLatitude(), station.getLongitude(),
                        graph.nextStationCount(station.getId())))
                .toList();
    }
}
