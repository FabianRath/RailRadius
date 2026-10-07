package com.railreach.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** A rail operator of a feed and the last day it runs a train; part of {@link FeedStatus}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorEnd {

    private String operator;
    private LocalDate lastServiceDate;
}
