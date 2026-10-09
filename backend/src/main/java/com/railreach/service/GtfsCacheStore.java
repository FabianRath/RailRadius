package com.railreach.service;

import com.railreach.model.Station;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Reads and writes the parsed {@link TransitGraph} as one JSON file, so restarts skip parsing the zips. */
@Component
@Slf4j
public class GtfsCacheStore {

    private static final long BYTES_PER_MB = 1024 * 1024;

    private final String cacheFile;

    private final ObjectMapper objectMapper = new ObjectMapper();

    GtfsCacheStore(@Value("${railreach.gtfs.cache-file:gtfs-cache.json}") String cacheFile) {
        this.cacheFile = cacheFile;
    }

    /** Bump on every format change: a cache file with another version is ignored and the zips are re-parsed. */
    static final int FORMAT_VERSION = 8;

    /**
     * Field names are the on-disk format. Only stations, their translated names, the services of the trips and the
     * trips are stored, see {@link CachedTrip}.
     * {@code formatVersion} is boxed so a file from before versioning (no such field) reads as null, not an error.
     */
    public record GtfsCacheData(Integer formatVersion, Map<String, Station> stations,
                                Map<String, Set<String>> translatedNames, Map<String, ServiceDays> services,
                                Map<String, CachedTrip> trips) {}

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
            GtfsCacheData data = objectMapper.readValue(file, GtfsCacheData.class);
            if (!Objects.equals(data.formatVersion(), FORMAT_VERSION)) {
                log.warn("GTFS cache has format version {}, expected {}; ignoring it", data.formatVersion(),
                        FORMAT_VERSION);
                return Optional.empty();
            }
            return Optional.of(data);
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
            log.info("  Serializing {} stations, {} trips...", data.stations().size(), data.trips().size());
            objectMapper.writeValue(file, data);
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
