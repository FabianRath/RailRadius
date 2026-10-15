package com.railreach.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** A served feed whose trains end within the alert days, or already have; part of {@link FeedAlertStatus}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeedAlert {

    private String source;
    private LocalDate lastServiceDate;
    /** The last service day, or the earlier last day of a main operator that stops early. */
    private LocalDate trainsEnd;
    /** Days until {@link #trainsEnd}; negative once it is past. */
    private long daysLeft;
    private List<OperatorEnd> earlyEndingOperators;
    /** Paused feeds are left out of the alert mail until their trains end on another day. */
    private boolean paused;
}
