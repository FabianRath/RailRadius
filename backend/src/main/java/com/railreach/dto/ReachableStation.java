package com.railreach.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReachableStation {
    private String stopId;
    private String stopName;
    private Double latitude;
    private Double longitude;
    private Integer transfers;
    private String totalDuration;
    private Integer totalDurationMinutes;
    private List<String> path;
    private List<String> pathNames;
    private List<JourneyLeg> legs;
}
