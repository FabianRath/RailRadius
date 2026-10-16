package com.railradius.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome of a test alert mail. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeedAlertMailResult {

    private boolean sent;
    private String message;
}
