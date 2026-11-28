package com.railradius.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JourneyLeg {
    private String type;  // "TRAIN" or "TRANSFER"
    private String trainName;
    // Train number, direction and operator, for finding the train on the operator's site; null on TRANSFER legs
    private String trainNumber;
    private String headsign;
    private String operator;
    private String fromStation;
    private String toStation;
    // Canonical stop IDs as in ReachableStation.path, so the map can split the path into legs
    private String fromStopId;
    private String toStopId;
    private String fromCountry;
    private String toCountry;
    private String departureTime;
    private String arrivalTime;
    private String duration;
    private Integer durationMinutes;
    // TRAIN legs, when the server has a track network (#110): per pair of consecutive stops its line along the
    // tracks as [lon, lat] points, null for a pair the tracks don't join. Null without a network
    private List<float[][]> track;
}
