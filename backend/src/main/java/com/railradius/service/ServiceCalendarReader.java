package com.railradius.service;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipFile;

import static com.railradius.service.GtfsCsvReader.field;

/** Reads the days each service of one feed runs from calendar.txt and calendar_dates.txt. */
@Slf4j
final class ServiceCalendarReader {

    private static final List<String> WEEKDAY_COLUMNS =
            List.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");
    private static final String RUNS_ON_WEEKDAY = "1";
    private static final String SERVICE_ADDED = "1";
    private static final String SERVICE_REMOVED = "2";

    private ServiceCalendarReader() {}

    /** One service's days while the files are read; bit i is epoch day baseDay + i. */
    private static final class DayBits {
        private int baseDay;
        private BitSet bits = new BitSet();

        void set(int epochDay, boolean runs) {
            if (!runs) {
                if (epochDay >= baseDay) bits.clear(epochDay - baseDay);
                return;
            }
            if (bits.isEmpty()) {
                baseDay = epochDay;
            } else if (epochDay < baseDay) {
                rebase(epochDay);
            }
            bits.set(epochDay - baseDay);
        }

        // Rare: calendar_dates.txt adds a day before every day seen so far
        private void rebase(int newBaseDay) {
            BitSet moved = new BitSet();
            int shift = baseDay - newBaseDay;
            bits.stream().forEach(i -> moved.set(i + shift));
            bits = moved;
            baseDay = newBaseDay;
        }
    }

    /** raw service ID -> its days. Empty if the feed has neither file. */
    static Map<String, ServiceDays> load(ZipFile zipFile) throws IOException {
        Map<String, DayBits> services = new HashMap<>();
        int malformedRows = readWeeklyPatterns(zipFile, services) + readExceptions(zipFile, services);
        if (malformedRows > 0) log.warn("  Calendar: {} rows with a malformed date skipped", malformedRows);
        Map<String, ServiceDays> result = new HashMap<>(services.size() * 2);
        services.forEach((id, days) -> result.put(id, ServiceDays.of(days.bits, days.baseDay)));
        log.info("  Calendar: {} services", result.size());
        return result;
    }

    /** Returns the number of malformed rows. */
    private static int readWeeklyPatterns(ZipFile zipFile, Map<String, DayBits> services) throws IOException {
        int malformedRows = 0;
        try (GtfsCsvReader csv = GtfsCsvReader.open(zipFile, "calendar.txt")) {
            int idCol = csv.column("service_id");
            int startCol = csv.column("start_date");
            int endCol = csv.column("end_date");
            int[] weekdayCols = WEEKDAY_COLUMNS.stream().mapToInt(csv::column).toArray();
            for (String[] row = csv.next(); row != null; row = csv.next()) {
                String id = field(row, idCol);
                Optional<LocalDate> start = date(field(row, startCol));
                Optional<LocalDate> end = date(field(row, endCol));
                if (id == null || start.isEmpty() || end.isEmpty()) {
                    malformedRows++;
                    continue;
                }
                DayBits days = services.computeIfAbsent(id, k -> new DayBits());
                for (LocalDate day = start.get(); !day.isAfter(end.get()); day = day.plusDays(1)) {
                    int weekdayCol = weekdayCols[day.getDayOfWeek().getValue() - 1];
                    if (RUNS_ON_WEEKDAY.equals(field(row, weekdayCol))) days.set((int) day.toEpochDay(), true);
                }
            }
        }
        return malformedRows;
    }

    /** Returns the number of malformed rows. */
    private static int readExceptions(ZipFile zipFile, Map<String, DayBits> services) throws IOException {
        int malformedRows = 0;
        try (GtfsCsvReader csv = GtfsCsvReader.open(zipFile, "calendar_dates.txt")) {
            int idCol = csv.column("service_id");
            int dateCol = csv.column("date");
            int typeCol = csv.column("exception_type");
            for (String[] row = csv.next(); row != null; row = csv.next()) {
                String id = field(row, idCol);
                Optional<LocalDate> date = date(field(row, dateCol));
                String type = field(row, typeCol);
                if (id == null || date.isEmpty() || !(SERVICE_ADDED.equals(type) || SERVICE_REMOVED.equals(type))) {
                    malformedRows++;
                    continue;
                }
                services.computeIfAbsent(id, k -> new DayBits())
                        .set((int) date.get().toEpochDay(), SERVICE_ADDED.equals(type));
            }
        }
        return malformedRows;
    }

    /** Parses a GTFS date ({@code YYYYMMDD}). */
    private static Optional<LocalDate> date(String text) {
        if (text == null) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
