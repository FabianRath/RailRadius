package com.railradius.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a browser searches with (#9, #12), returned by {@code GET /api/browser-data}. A browser whose data version,
 * engine version and today all match searches on its own copy; any difference means it would find other stations.
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
    // The engine the page loads (frontend/engine/search.wasm), as a content version like the data's
    private String engineVersion;
    // The server's today (yyyy-mm-dd), which decides the days already past; the engine must search with it
    private String today;
}
