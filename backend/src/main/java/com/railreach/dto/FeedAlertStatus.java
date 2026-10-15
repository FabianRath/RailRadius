package com.railreach.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** The feed alert mail (F29): whether it is on and can be sent, and the feeds it is about; for the admin panel. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeedAlertStatus {

    private boolean enabled;
    /** False while the SMTP server or the recipient is not configured; then nothing is mailed. */
    private boolean mailConfigured;
    private String recipient;
    private int alertDays;
    /** Served feeds whose trains end within {@link #alertDays}, the earliest first. */
    private List<FeedAlert> feeds;
}
