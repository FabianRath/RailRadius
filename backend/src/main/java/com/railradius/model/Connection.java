package com.railradius.model;

import com.railradius.util.GtfsTime;
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
    // Position of the trip in the store; only set on a search result's connections, for the journey's trip infos
    private int storeTrip;
    private String routeName;
    private String fromStopId;
    private String toStopId;
    
    // Seconds since midnight, so the BFS never parses time strings
    private int departureTimeSeconds;
    private int arrivalTimeSeconds;
    
    public String getDepartureTime() {
        return GtfsTime.format(departureTimeSeconds);
    }

    public String getArrivalTime() {
        return GtfsTime.format(arrivalTimeSeconds);
    }
}
