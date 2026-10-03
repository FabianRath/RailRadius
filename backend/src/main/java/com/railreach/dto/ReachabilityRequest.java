package com.railreach.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReachabilityRequest {
    private String startStationId;
    private Integer maxTransfers;
    private Double maxJourneyTimeHours;
    private Integer minTransferTimeMinutes;
    private Integer maxTransferTimeMinutes;
}
