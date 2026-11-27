package com.railradius.controller;

import com.railradius.dto.RailTileJson;
import com.railradius.service.RailTileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RailTileControllerTest {

    private static final byte[] TILE = {0x1a, 0x02, 0x0a, 0x00};
    // As Planetiler writes it: numbers as strings
    private static final String METADATA = "{\"name\":\"RailRadius rail\",\"bounds\":\"9.5,45.7,22.9,49.6\","
            + "\"minzoom\":\"4\",\"maxzoom\":\"12\",\"format\":\"pbf\"}";

    @TempDir
    Path tempDir;

    @Test
    void withoutBuiltTilesThereIsNoTileJsonSoTheMapKeepsItsOwnRail() {
        RailTileController controller = controller();

        assertThat(controller.tileJson().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.tile(5, 17, 11).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void builtTilesAreDescribedAsTileJsonFromPlanetilersMetadata() throws IOException {
        Files.writeString(tempDir.resolve("metadata.json"), METADATA);

        RailTileJson tileJson = controller().tileJson().getBody();

        assertThat(tileJson).isNotNull();
        assertThat(tileJson.getTiles()).containsExactly("/tiles/rail/{z}/{x}/{y}.pbf");
        assertThat(tileJson.getMinzoom()).isEqualTo(4);
        assertThat(tileJson.getMaxzoom()).isEqualTo(12);
        assertThat(tileJson.getBounds()).isEqualTo(List.of(9.5, 45.7, 22.9, 49.6));
    }

    @Test
    void aBuiltTileIsServedAsVectorTile() throws IOException {
        Files.writeString(tempDir.resolve("metadata.json"), METADATA);
        Files.createDirectories(tempDir.resolve("5/17"));
        Files.write(tempDir.resolve("5/17/11.pbf"), TILE);

        ResponseEntity<Resource> response = controller().tile(5, 17, 11);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).hasToString("application/vnd.mapbox-vector-tile");
        assertThat(response.getBody().getContentAsByteArray()).isEqualTo(TILE);
    }

    @Test
    void aTileWithoutTrackInABuiltTilesetIsEmptyNotMissing() throws IOException {
        Files.writeString(tempDir.resolve("metadata.json"), METADATA);

        assertThat(controller().tile(5, 17, 11).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void unreadableMetadataCountsAsNoTiles() throws IOException {
        Files.writeString(tempDir.resolve("metadata.json"), "{\"minzoom\":\"four\"}");

        assertThat(controller().tileJson().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private RailTileController controller() {
        return new RailTileController(new RailTileStore(tempDir.toString(), JsonMapper.builder().build()));
    }
}
