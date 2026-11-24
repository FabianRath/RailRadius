package com.railradius.service;

import com.railradius.dto.JourneyLeg;
import com.railradius.dto.ReachableStation;
import com.railradius.dto.ReachableStationSummary;
import com.railradius.model.Connection;
import com.railradius.model.JourneyPath;
import com.railradius.model.Station;
import com.railradius.util.GtfsTime;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Turns a search's journey into the API's {@link ReachableStation}, with the rides and transfers as legs, or a path's
 * summary into a {@link ReachableStationSummary}. Stations and trips are looked up in the graph the result was
 * searched on ({@link CompactResult#served}), so a result never mixes the graph from before a load with the new one.
 */
@Component
public class JourneyLegBuilder {

    private static final String LEG_TRAIN = "TRAIN";
    private static final String LEG_TRANSFER = "TRANSFER";
    // Rounding in the feeds can put a few seconds between arriving and departing on the same train
    private static final int SAME_RIDE_MAX_GAP_MINUTES = 1;

    /** Empty if the destination station is unknown. */
    public Optional<ReachableStation> toReachableStation(CompactResult result, String stopId, int transfers) {
        if (result.served().station(stopId) == null) return Optional.empty();
        return toReachableStation(result.served(), stopId, result.journey(stopId, transfers));
    }

    /** The journey to stopId on the graph whose store it was built from; empty if the station is unknown. */
    Optional<ReachableStation> toReachableStation(ServedGraph graph, String stopId, CompactResult.Journey journey) {
        Station station = graph.station(stopId);
        if (station == null) return Optional.empty();
        JourneyPath path = journey.path();
        ServiceDays runningDays = journey.runningDays();
        return Optional.of(ReachableStation.builder()
                .stopId(stopId)
                .stopName(station.getName())
                .country(station.getCountry())
                .latitude(station.getLatitude())
                .longitude(station.getLongitude())
                .transfers(path.getTransfers())
                .totalDuration(GtfsTime.formatDuration(path.getTotalDurationMinutes()))
                .totalDurationMinutes(path.getTotalDurationMinutes())
                .path(path.getStationIds())
                .pathNames(path.getStationIds().stream().map(id -> stationName(graph, id)).toList())
                .legs(buildLegs(graph, path.getConnections()))
                .runningDays(JourneyRunningDays.describe(runningDays).orElse(null))
                .hasCommonDay(!runningDays.isEmpty())
                .roundTripStation(roundTripStation(path.getStationIds()).map(id -> stationName(graph, id)).orElse(null))
                .build());
    }

    /** The station and the path's transfers and duration, without the journey; empty if the station is unknown. */
    public Optional<ReachableStationSummary> toSummary(ServedGraph graph, String stopId,
                                                       CompactResult.PathSummary path) {
        Station station = graph.station(stopId);
        if (station == null) return Optional.empty();
        return Optional.of(ReachableStationSummary.builder()
                .stopId(stopId)
                .stopName(station.getName())
                .country(station.getCountry())
                .latitude(station.getLatitude())
                .longitude(station.getLongitude())
                .transfers(path.transfers())
                .totalDuration(GtfsTime.formatDuration(path.durationMinutes()))
                .totalDurationMinutes(path.durationMinutes())
                .build());
    }

    /**
     * One TRAIN leg per ride and a TRANSFER leg between rides. Consecutive connections are one ride if they
     * share the trip, or the route name with no gap (the same train split across trip_id variants).
     */
    private static List<JourneyLeg> buildLegs(ServedGraph graph, List<Connection> connections) {
        List<JourneyLeg> legs = new ArrayList<>();
        int rideStart = 0;
        while (rideStart < connections.size()) {
            int rideEnd = rideStart + 1;
            while (rideEnd < connections.size() && isSameRide(connections.get(rideEnd - 1), connections.get(rideEnd))) {
                rideEnd++;
            }
            Connection last = connections.get(rideEnd - 1);
            legs.add(trainLeg(graph, connections.get(rideStart), last));

            if (rideEnd < connections.size()) {
                int transferMinutes = Math.max(0, GtfsTime.diffMinutes(
                        last.getArrivalTimeSeconds(), connections.get(rideEnd).getDepartureTimeSeconds()));
                String transferStopId = last.getToStopId();
                String transferStation = stationName(graph, transferStopId);
                String transferCountry = stationCountry(graph, transferStopId);
                legs.add(JourneyLeg.builder()
                        .type(LEG_TRANSFER)
                        .fromStation(transferStation)
                        .toStation(transferStation)
                        .fromStopId(transferStopId)
                        .toStopId(transferStopId)
                        .fromCountry(transferCountry)
                        .toCountry(transferCountry)
                        .duration(GtfsTime.formatDuration(transferMinutes))
                        .durationMinutes(transferMinutes)
                        .build());
            }
            rideStart = rideEnd;
        }
        return legs;
    }

    private static JourneyLeg trainLeg(ServedGraph graph, Connection first, Connection last) {
        int rideMinutes = GtfsTime.diffMinutes(first.getDepartureTimeSeconds(), last.getArrivalTimeSeconds());
        Optional<TripInfo> tripInfo = graph.tripInfo(first.getTripId());
        return JourneyLeg.builder()
                .type(LEG_TRAIN)
                .trainName(first.getRouteName())
                .trainNumber(tripInfo.map(TripInfo::trainNumber).orElse(null))
                .headsign(tripInfo.map(TripInfo::headsign).orElse(null))
                .operator(tripInfo.map(TripInfo::operator).orElse(null))
                .fromStation(stationName(graph, first.getFromStopId()))
                .toStation(stationName(graph, last.getToStopId()))
                .fromStopId(first.getFromStopId())
                .toStopId(last.getToStopId())
                .fromCountry(stationCountry(graph, first.getFromStopId()))
                .toCountry(stationCountry(graph, last.getToStopId()))
                .departureTime(first.getDepartureTime())
                .arrivalTime(last.getArrivalTime())
                .duration(GtfsTime.formatDuration(rideMinutes))
                .durationMinutes(rideMinutes)
                .build();
    }

    private static boolean isSameRide(Connection prev, Connection next) {
        if (prev.getTripId().equals(next.getTripId())) return true;
        boolean sameName = prev.getRouteName() != null && prev.getRouteName().equals(next.getRouteName());
        return sameName && GtfsTime.diffMinutes(prev.getArrivalTimeSeconds(), next.getDepartureTimeSeconds())
                <= SAME_RIDE_MAX_GAP_MINUTES;
    }

    /**
     * The first station the path passes more than once (F24). The outermost one, so for a ride out and back on the
     * same line it is where the loop starts, not a stop on the way.
     */
    private static Optional<String> roundTripStation(List<String> stationIds) {
        Map<String, Long> visits = stationIds.stream().collect(Collectors.groupingBy(id -> id, Collectors.counting()));
        return stationIds.stream().filter(id -> visits.get(id) > 1).findFirst();
    }

    /** Falls back to the raw stop ID when the station is unknown (see F2). */
    private static String stationName(ServedGraph graph, String stopId) {
        Station s = graph.station(stopId);
        return s != null ? s.getName() : stopId;
    }

    private static String stationCountry(ServedGraph graph, String stopId) {
        Station s = graph.station(stopId);
        return s != null ? s.getCountry() : null;
    }
}
