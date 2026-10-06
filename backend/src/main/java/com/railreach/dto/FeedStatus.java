package com.railreach.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/** One GTFS zip in the feed directory (or an expected one without a zip) and how long its timetable runs; returned by {@code GET /api/admin/feeds}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeedStatus {

    /**
     * Declared in the order the admin panel lists feeds: the ones that need action first. {@code MISSING}: an
     * expected feed (see {@code railreach.feeds.sources}) has no zip yet.
     */
    public enum State { MISSING, EXPIRED, NOT_YET_VALID, NO_CALENDAR, UNREADABLE, EXPIRING, OK }

    private String source;
    private String fileName;
    private long sizeBytes;
    /** Last modification of the zip, i.e. when it was downloaded. */
    private Instant downloadedAt;
    /** First and last day any service of the feed runs; null without a calendar. */
    private LocalDate firstServiceDate;
    private LocalDate lastServiceDate;
    /** Days from today to {@code lastServiceDate}, negative once expired; null without a calendar. */
    private Long daysLeft;
    private State state;
    /** From {@code gtfs-feeds.txt}; null for feeds that are downloaded by hand. */
    private String downloadUrl;
}
