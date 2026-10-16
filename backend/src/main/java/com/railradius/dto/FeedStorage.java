package com.railradius.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Disk space of the GTFS zips: the ones the loader reads and the replaced ones kept in {@code previous/}; returned by
 * {@code GET /api/admin/feeds/storage}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeedStorage {

    private int inUseCount;
    private long inUseBytes;
    private int previousCount;
    private long previousBytes;
}
