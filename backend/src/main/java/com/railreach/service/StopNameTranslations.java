package com.railreach.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static com.railreach.service.GtfsCsvReader.field;

/**
 * The translated stop names of one feed's {@code translations.txt}. A translation names its stop by ID
 * ({@code record_id}) or by the untranslated name ({@code field_value}, or {@code trans_id} in the pre-2019 format,
 * which has no {@code table_name} and translates any field by its text).
 */
record StopNameTranslations(Map<String, Set<String>> byStopId, Map<String, Set<String>> byName) {

    private static final String STOPS_TABLE = "stops";
    private static final String STOP_NAME_FIELD = "stop_name";

    static StopNameTranslations load(ZipFile zipFile) throws IOException {
        Map<String, Set<String>> byStopId = new HashMap<>();
        Map<String, Set<String>> byName = new HashMap<>();
        try (GtfsCsvReader csv = GtfsCsvReader.open(zipFile, "translations.txt")) {
            int tableCol = csv.column("table_name");
            int fieldCol = csv.column("field_name");
            int recordCol = csv.column("record_id");
            int valueCol = csv.column("field_value");
            int legacyIdCol = csv.column("trans_id");
            int translationCol = csv.column("translation");
            for (String[] row = csv.next(); row != null; row = csv.next()) {
                String translation = field(row, translationCol);
                if (translation == null) continue;
                if (legacyIdCol >= 0) {
                    add(byName, field(row, legacyIdCol), translation);
                } else if (STOPS_TABLE.equals(field(row, tableCol)) && STOP_NAME_FIELD.equals(field(row, fieldCol))) {
                    String recordId = field(row, recordCol);
                    if (recordId != null) {
                        add(byStopId, recordId, translation);
                    } else {
                        add(byName, field(row, valueCol), translation);
                    }
                }
            }
        }
        return new StopNameTranslations(byStopId, byName);
    }

    /** The stop's translated names, by its raw ID and by its name. */
    Set<String> of(String rawStopId, String stopName) {
        Set<String> names = new HashSet<>(byStopId.getOrDefault(rawStopId, Set.of()));
        if (stopName != null) names.addAll(byName.getOrDefault(stopName, Set.of()));
        return names;
    }

    private static void add(Map<String, Set<String>> translations, String key, String translation) {
        if (key != null) translations.computeIfAbsent(key, k -> new HashSet<>()).add(translation);
    }
}
