package com.railreach.service;

import com.railreach.dto.JourneyLeg;
import com.railreach.dto.ReachableStation;
import com.railreach.model.Connection;
import com.railreach.model.JourneyPath;
import com.railreach.model.Station;
import com.railreach.util.GtfsTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Turns a BFS path into the API's {@link ReachableStation}, with the rides and transfers as legs. */
@Component
@RequiredArgsConstructor
public class JourneyLegBuilder {

    private static final String LEG_TRAIN = "TRAIN";
    private static final String LEG_TRANSFER = "TRANSFER";
    // Rounding in the feeds can put a few seconds between arriving and departing on the same train
    private static final int SAME_RIDE_MAX_GAP_MINUTES = 1;

    private final TransitGraph transitGraph;

    /** Empty if the destination station is unknown. */
    public Optional<ReachableStation> toReachableStation(String stopId, JourneyPath path) {
        Station station = transitGraph.getStation(stopId);
        if (station == null) return Optional.empty();
        return Optional.of(ReachableStation.builder()
                .stopId(stopId)
                .stopName(station.getName())
                .latitude(station.getLatitude())
                .longitude(station.getLongitude())
                .transfers(path.getTransfers())
                .totalDuration(GtfsTime.formatDuration(path.getTotalDurationMinutes()))
                .totalDurationMinutes(path.getTotalDurationMinutes())
                .path(path.getStationIds())
                .pathNames(path.getStationIds().stream().map(this::stationName).toList())
                .legs(buildLegs(path.getConnections()))
                .build());
    }

    /**
     * One TRAIN leg per ride and a TRANSFER leg between rides. Consecutive connections are one ride if they
     * share the trip, or the route name with no gap (the same train split across trip_id variants).
     */
    private List<JourneyLeg> buildLegs(List<Connection> connections) {
        List<JourneyLeg> legs = new ArrayList<>();
        int rideStart = 0;
        while (rideStart < connections.size()) {
            int rideEnd = rideStart + 1;
            while (rideEnd < connections.size() && isSameRide(connections.get(rideEnd - 1), connections.get(rideEnd))) {
                rideEnd++;
            }
            Connection first = connections.get(rideStart);
            Connection last = connections.get(rideEnd - 1);
            int rideMinutes = GtfsTime.diffMinutes(first.getDepartureTimeSeconds(), last.getArrivalTimeSeconds());
            legs.add(JourneyLeg.builder()
                    .type(LEG_TRAIN)
                    .trainName(first.getRouteName())
                    .fromStation(stationName(first.getFromStopId()))
                    .toStation(stationName(last.getToStopId()))
                    .departureTime(first.getDepartureTime())
                    .arrivalTime(last.getArrivalTime())
                    .duration(GtfsTime.formatDuration(rideMinutes))
                    .durationMinutes(rideMinutes)
                    .build());

            if (rideEnd < connections.size()) {
                int transferMinutes = Math.max(0, GtfsTime.diffMinutes(
                        last.getArrivalTimeSeconds(), connections.get(rideEnd).getDepartureTimeSeconds()));
                String transferStation = stationName(last.getToStopId());
                legs.add(JourneyLeg.builder()
                        .type(LEG_TRANSFER)
                        .fromStation(transferStation)
                        .toStation(transferStation)
                        .duration(GtfsTime.formatDuration(transferMinutes))
                        .durationMinutes(transferMinutes)
                        .build());
            }
            rideStart = rideEnd;
        }
        return legs;
    }

    private static boolean isSameRide(Connection prev, Connection next) {
        if (prev.getTripId().equals(next.getTripId())) return true;
        boolean sameName = prev.getRouteName() != null && prev.getRouteName().equals(next.getRouteName());
        return sameName && GtfsTime.diffMinutes(prev.getArrivalTimeSeconds(), next.getDepartureTimeSeconds())
                <= SAME_RIDE_MAX_GAP_MINUTES;
    }

    /** Falls back to the raw stop ID when the station is unknown (see F2). */
    private String stationName(String stopId) {
        Station s = transitGraph.getStation(stopId);
        return s != null ? s.getName() : stopId;
    }
}
