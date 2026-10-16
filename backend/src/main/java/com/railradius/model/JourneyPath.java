package com.railradius.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JourneyPath {
    private List<String> stationIds;
    private List<Connection> connections;
    private Integer transfers;
    private Integer totalDurationMinutes;
}
