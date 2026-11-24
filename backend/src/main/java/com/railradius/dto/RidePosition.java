package com.railradius.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One ride of a browser's journey as positions in the timetable store: its trip, board and alight stop event. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RidePosition {
    private int trip;
    private int boardEvent;
    private int alightEvent;
}
