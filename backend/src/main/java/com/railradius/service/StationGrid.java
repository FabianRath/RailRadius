package com.railradius.service;

import com.railradius.model.Station;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stations bucketed into square cells of {@code cellSizeDeg}, so only nearby stations are compared. */
final class StationGrid {

    private final double cellSizeDeg;
    private final Map<Long, List<Station>> byCell = new HashMap<>();

    StationGrid(Collection<Station> stations, double cellSizeDeg) {
        this.cellSizeDeg = cellSizeDeg;
        for (Station station : stations) {
            byCell.computeIfAbsent(cellKey(cellX(station), cellY(station)), k -> new ArrayList<>()).add(station);
        }
    }

    /** The stations in the 3×3 block of cells around the station's own cell, the station itself included. */
    List<Station> near(Station station) {
        List<Station> result = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                result.addAll(byCell.getOrDefault(cellKey(cellX(station) + dx, cellY(station) + dy), List.of()));
            }
        }
        return result;
    }

    private int cellX(Station station) {
        return (int) Math.floor(station.getLatitude() / cellSizeDeg);
    }

    private int cellY(Station station) {
        return (int) Math.floor(station.getLongitude() / cellSizeDeg);
    }

    private static long cellKey(int cellX, int cellY) {
        return ((long) cellX << Integer.SIZE) | (cellY & 0xFFFFFFFFL);
    }
}
