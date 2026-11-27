package com.railradius.service;

import com.railradius.dto.RailTileJson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The rail-only basemap tiles (#93) that scripts/rail-tiles.sh builds: Planetiler's {z}/{x}/{y}.pbf folders and its
 * metadata.json. Read from disk on every request, so a rebuild is served without a restart.
 */
@Service
@Slf4j
public class RailTileStore {

    public static final String TILE_PATH = "/tiles/rail/";
    private static final String TILE_TEMPLATE = TILE_PATH + "{z}/{x}/{y}.pbf";
    private static final String TILEJSON_VERSION = "3.0.0";
    private static final String METADATA_FILE = "metadata.json";

    private final Path directory;
    private final ObjectMapper objectMapper;

    public RailTileStore(@Value("${railradius.rail-tiles.directory:data/rail-tiles}") String directory,
                         ObjectMapper objectMapper) {
        this.directory = Path.of(directory);
        this.objectMapper = objectMapper;
    }

    /** Empty while no tiles are built, or their metadata is unreadable. */
    public Optional<RailTileJson> tileJson() {
        Path metadata = directory.resolve(METADATA_FILE);
        if (!Files.isRegularFile(metadata)) return Optional.empty();
        try {
            // Planetiler writes the numbers as strings
            JsonNode node = objectMapper.readTree(metadata.toFile());
            List<Double> bounds = Arrays.stream(node.path("bounds").asString().split(",")).map(Double::valueOf)
                    .toList();
            return Optional.of(new RailTileJson(TILEJSON_VERSION, List.of(TILE_TEMPLATE),
                    Integer.parseInt(node.path("minzoom").asString()), Integer.parseInt(node.path("maxzoom").asString()),
                    bounds));
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Could not read the rail tile metadata {}", metadata, e);
            return Optional.empty();
        }
    }

    public boolean isBuilt() {
        return Files.isRegularFile(directory.resolve(METADATA_FILE));
    }

    /** The tile's file; empty where it has no track, as Planetiler writes no empty tiles. */
    public Optional<Path> tile(int zoom, int x, int y) {
        Path file = directory.resolve(Integer.toString(zoom)).resolve(Integer.toString(x)).resolve(y + ".pbf");
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }
}
