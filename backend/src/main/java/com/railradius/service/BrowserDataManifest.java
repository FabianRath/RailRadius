package com.railradius.service;

import com.railradius.dto.BrowserDataInfo;
import com.railradius.util.ContentVersion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * What a browser needs to search exactly as the server does (#12): the served graph's data file, the version of the
 * engine the page loads, and the server's today, which decides the days already past. A browser that holds anything
 * else would find other stations, so it searches on the server.
 */
@Service
@Slf4j
public class BrowserDataManifest {

    private static final String DATA_PATH = "/api/browser-data/";
    // The data file's path ends in it, after the version
    public static final String DATA_SUFFIX = ".bin";
    // Relative to the frontend's static location, where browser-engine/ builds it
    private static final String ENGINE_PATH = "engine/search.wasm";

    private final BrowserDataStore dataStore;
    private final TransitGraph graph;
    private final Resource engine;
    // The engine's version by the file's modification time and size: the frontend is served from disk, so a deploy
    // can replace the engine while the app runs
    private volatile EngineFile engineFile;

    private record EngineFile(long lastModifiedMs, long sizeBytes, String version) {}

    public BrowserDataManifest(BrowserDataStore dataStore, TransitGraph graph, ResourceLoader resourceLoader,
                               @Value("${spring.web.resources.static-locations:classpath:/static/}")
                               String staticLocation) {
        this.dataStore = dataStore;
        this.graph = graph;
        this.engine = resourceLoader.getResource(staticLocation + ENGINE_PATH);
    }

    /** Empty while the data file is built, or if the engine is not built: browsers then search on the server. */
    public Optional<BrowserDataInfo> current() {
        Optional<BrowserDataStore.Published> data = dataStore.current();
        Optional<String> engineVersion = engineVersion();
        if (data.isEmpty() || engineVersion.isEmpty()) return Optional.empty();
        BrowserDataStore.Published published = data.get();
        return Optional.of(new BrowserDataInfo(published.version(), DATA_PATH + published.version() + DATA_SUFFIX,
                published.sizeBytes(), engineVersion.get(), LocalDate.ofEpochDay(graph.todayEpochDay()).toString()));
    }

    /** The file of that version, if it is the current one. */
    public Optional<BrowserDataStore.Published> file(String version) {
        return dataStore.current().filter(published -> published.version().equals(version));
    }

    private Optional<String> engineVersion() {
        try {
            if (!engine.exists()) return Optional.empty();
            long lastModifiedMs = engine.lastModified();
            long sizeBytes = engine.contentLength();
            EngineFile known = engineFile;
            if (known == null || known.lastModifiedMs() != lastModifiedMs || known.sizeBytes() != sizeBytes) {
                known = new EngineFile(lastModifiedMs, sizeBytes, ContentVersion.of(engine.getContentAsByteArray()));
                engineFile = known;
            }
            return Optional.of(known.version());
        } catch (IOException e) {
            log.warn("Could not read the browser engine {}", engine, e);
            return Optional.empty();
        }
    }
}
