package com.railradius.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
