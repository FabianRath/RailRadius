package com.railreach.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StopSequence {
    private String stopId;
    private Integer sequence;
    private String arrivalTime;
    private String departureTime;
}
