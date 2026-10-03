package com.railreach.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
    
    // OPTIMIZED: Store times as integers (seconds since midnight)
    // This avoids parsing "14:30:00" strings thousands of times during BFS
    private int departureTimeSeconds;
    private int arrivalTimeSeconds;
    
    private Integer stopsApart;
    private List<StopSequence> intermediateStops;
    
    // Convenience methods for display (and backwards-compatible deserialization)
    @JsonProperty("departureTime")
    public String getDepartureTime() {
        return formatTime(departureTimeSeconds);
    }
    
    @JsonProperty("arrivalTime")
    public String getArrivalTime() {
        return formatTime(arrivalTimeSeconds);
    }
    
    // Allow old cache files with string times to deserialize
    @JsonProperty("departureTime")
    public void setDepartureTime(String time) {
        this.departureTimeSeconds = parseTime(time);
    }
    
    @JsonProperty("arrivalTime")
    public void setArrivalTime(String time) {
        this.arrivalTimeSeconds = parseTime(time);
    }
    
    private static String formatTime(int seconds) {
        if (seconds < 0) return "00:00:00";
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
    
    private static int parseTime(String time) {
        if (time == null || time.isEmpty()) return 0;
        String[] parts = time.split(":");
        if (parts.length < 2) return 0;
        int hours = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);
        int secs = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        return hours * 3600 + minutes * 60 + secs;
    }
}
