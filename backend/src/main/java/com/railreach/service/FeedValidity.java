package com.railreach.service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.IntSummaryStatistics;
import java.util.Optional;
import java.util.zip.ZipFile;

/**
 * The first and last day any service of a feed runs. Read from the calendar of the whole feed, not only its rail
 * trips, so a zip can be checked without parsing trips.txt.
 */
record FeedValidity(LocalDate firstDay, LocalDate lastDay) {

    /** Empty if the feed has no calendar or no service runs on any day. */
    static Optional<FeedValidity> read(ZipFile zipFile) throws IOException {
        IntSummaryStatistics days = ServiceCalendarReader.load(zipFile).values().stream()
                .flatMapToInt(ServiceDays::epochDays)
                .summaryStatistics();
        if (days.getCount() == 0) return Optional.empty();
        return Optional.of(new FeedValidity(LocalDate.ofEpochDay(days.getMin()), LocalDate.ofEpochDay(days.getMax())));
    }
}
