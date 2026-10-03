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
public class ReachabilityResponse {
    private String startStationId;
    private String startStationName;
    private Integer maxTransfers;
    private Integer totalReachableStations;
    private Long calculationTimeMs;
    private List<ReachableStation> reachableStations;
}
