package com.railradius.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The browser data file of the served graph (#9), returned by {@code GET /api/browser-data}. A browser that has this
 * version searches on its own copy; any other version means the server's data changed.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrowserDataInfo {

    private String version;
    // Where to fetch the gzipped file; the response is cached for a year, as a new version gets a new path
    private String path;
    private long sizeBytes;
}
