package com.railreach.dto;

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
    private String fromStation;
    private String toStation;
    private String fromCountry;
    private String toCountry;
    private String departureTime;
    private String arrivalTime;
    private String duration;
    private Integer durationMinutes;
}
