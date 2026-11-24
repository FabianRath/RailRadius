package com.railradius.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A journey a browser's search found (#11): the version of the browser data it searched on, the wait at changes it
 * searched with (null for the default) and its rides in order.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RideListJourneyRequest {
    private String version;
    private Integer minTransferMinutes;
    private Integer maxTransferMinutes;
    private List<RidePosition> rides;
}
