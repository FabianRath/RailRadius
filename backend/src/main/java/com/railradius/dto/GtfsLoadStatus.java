package com.railradius.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Progress of the current or last GTFS load from the zips; returned by {@code GET /api/admin/gtfs-load-status}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GtfsLoadStatus {
    /** IDLE, PARSING, MERGING, SAVING, DONE or FAILED. */
    private String phase;
    private int feedsDone;
    private int feedsTotal;
    /** Zip being parsed, null outside PARSING. */
    private String currentFeed;
    /** Share of the zip bytes parsed, 0–100. */
    private double parsedPercent;
    private long elapsedSeconds;
    /** Estimated seconds until parsing is done; null when unknown. Merging and saving come on top. */
    private Long parseEtaSeconds;
    private int stationCount;
    private int connectionCount;
}
