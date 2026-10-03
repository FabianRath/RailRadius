package com.railreach.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.railreach.util.GtfsTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Connection {
    private String tripId;
    private String routeId;
    private String routeName;
    private String fromStopId;
    private String toStopId;
    
    // Seconds since midnight, so the BFS never parses time strings
    private int departureTimeSeconds;
    private int arrivalTimeSeconds;
    
    private Integer stopsApart;
    
    // Convenience methods for display (and backwards-compatible deserialization)
    @JsonProperty("departureTime")
    public String getDepartureTime() {
        return GtfsTime.format(departureTimeSeconds);
    }
    
    @JsonProperty("arrivalTime")
    public String getArrivalTime() {
        return GtfsTime.format(arrivalTimeSeconds);
    }
    
    // Allow old cache files with string times to deserialize
    @JsonProperty("departureTime")
    public void setDepartureTime(String time) {
        this.departureTimeSeconds = GtfsTime.parseSeconds(time);
    }
    
    @JsonProperty("arrivalTime")
    public void setArrivalTime(String time) {
        this.arrivalTimeSeconds = GtfsTime.parseSeconds(time);
    }
}
