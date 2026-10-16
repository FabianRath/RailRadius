package com.railradius.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome of downloading a new version of one feed; the graph only uses it after a GTFS reload. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeedDownloadResult {

    /** BUSY: a GTFS reload or another download was running, nothing was downloaded. */
    public enum Outcome { REPLACED, REJECTED, BUSY }

    private String source;
    private Outcome outcome;
    private String message;
    /** Null if the feed had no zip yet. */
    private FeedStatus before;
    /** Null unless REPLACED. */
    private FeedStatus after;
}
