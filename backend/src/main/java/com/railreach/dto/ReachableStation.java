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
    private String country;
    private Double latitude;
    private Double longitude;
    private Integer transfers;
    private String totalDuration;
    private Integer totalDurationMinutes;
    private List<String> path;
    private List<String> pathNames;
    private List<JourneyLeg> legs;
    // When every train of the journey runs, e.g. "Runs Mon–Fri" or "Irregular: 2 days in the timetable"; null without calendar data
    private String runningDays;
    // False if the trains share no day: the search only checks consecutive trains (B25)
    private Boolean hasCommonDay;
}
