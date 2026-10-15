package com.railreach.service;

import com.railreach.dto.OperatorEnd;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipFile;

/**
 * How long a feed's trains run: the days of its calendar, and its main rail operators that stop before the last one
 * (B34), earliest first. Read from a zip by the update center, and stored with the served data so a server without
 * zips knows it too.
 */
record FeedTimespan(String source, FeedValidity validity, List<OperatorEnd> earlyEndingOperators) {

    /** {@code calendar} as read by {@link ServiceCalendarReader#load}; empty if no service runs on any day. */
    static Optional<FeedTimespan> read(ZipFile zipFile, String source, Map<String, ServiceDays> calendar)
            throws IOException {
        Optional<FeedValidity> validity = FeedValidity.of(calendar);
        if (validity.isEmpty()) return Optional.empty();
        List<OperatorEnd> earlyEnding = EarlyEndingOperators.read(zipFile, source, calendar, validity.get().lastDay());
        return Optional.of(new FeedTimespan(source, validity.get(), earlyEnding));
    }

    /** The last day all of its main operators still run trains. */
    LocalDate trainsEnd() {
        return earlyEndingOperators.isEmpty()
                ? validity.lastDay() : earlyEndingOperators.getFirst().getLastServiceDate();
    }
}
