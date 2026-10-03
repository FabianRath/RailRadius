package com.railreach.service;

import com.railreach.model.Connection;
import com.railreach.model.Station;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reads and writes the parsed {@link TransitGraph} as one JSON file, so restarts skip parsing the zips. */
@Component
@Slf4j
public class GtfsCacheStore {

    private static final long BYTES_PER_MB = 1024 * 1024;

    @Value("${railreach.gtfs.cache-file:gtfs-cache.json}")
    private String cacheFile;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Field names are the on-disk format; renaming one invalidates existing cache files. */
    public record GtfsCacheData(
            Map<String, Station> stations,
            Map<String, Set<String>> graph,
            Map<String, List<Connection>> connectionInfo,
            Map<String, List<String>> tripStops,
            Map<String, Set<String>> stopTrips,
            long timestamp,
            int stationCount,
            int graphNodeCount) {}

    Optional<GtfsCacheData> load() {
        File file = file();
        log.info("Looking for GTFS cache at: {}", file.getAbsolutePath());
        if (!file.exists()) {
            log.info("❌ No GTFS cache file found");
            return Optional.empty();
        }
        try {
            log.info("✓ Cache file exists ({} MB)", sizeMb(file));
            log.info("  Loading GTFS cache...");
            return Optional.of(objectMapper.readValue(file, GtfsCacheData.class));
        } catch (Exception e) {
            log.error("❌ FAILED to load GTFS cache!", e);
            return Optional.empty();
        }
    }

    void save(GtfsCacheData data) {
        File file = file();
        try {
            log.info("Saving GTFS cache to {}", file.getAbsolutePath());
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            log.info("  Serializing {} stations, {} connections...", data.stationCount(), data.connectionInfo().size());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, data);
            log.info("✓ GTFS cache saved ({} MB)", sizeMb(file));
        } catch (Exception e) {
            log.error("❌ FAILED to save GTFS cache to {}", file.getAbsolutePath(), e);
        }
    }

    void delete() {
        File file = file();
        if (file.exists() && file.delete()) {
            log.info("✓ GTFS cache deleted");
        }
    }

    String path() {
        return file().getAbsolutePath();
    }

    boolean exists() {
        return file().exists();
    }

    long sizeMb() {
        return exists() ? sizeMb(file()) : 0;
    }

    private File file() {
        return new File(cacheFile).getAbsoluteFile();
    }

    private static long sizeMb(File file) {
        return file.length() / BYTES_PER_MB;
    }
}
