package com.railreach.service;

import com.railreach.service.FeedRegistry.FeedSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedRegistryTest {

    @Test
    void lineWithHeadersParsesIntoUrlAndHeaders() {
        FeedSource feed = FeedRegistry.parse("finland;https://example.org/f.zip;Accept-Encoding: gzip|X-User: Rail");

        assertThat(feed.source()).isEqualTo("finland");
        assertThat(feed.url()).isEqualTo("https://example.org/f.zip");
        assertThat(feed.headers()).isEqualTo(Map.of("Accept-Encoding", "gzip", "X-User", "Rail"));
    }

    @Test
    void lineWithoutHeadersHasNone() {
        assertThat(FeedRegistry.parse("germany;https://example.org/de").headers()).isEmpty();
    }

    @Test
    void lineWithoutUrlIsInvalid() {
        assertThatThrownBy(() -> FeedRegistry.parse("austria")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registryFileListsFeedsWithTheirHeaders() {
        FeedRegistry registry = new FeedRegistry();

        assertThat(registry.of("finland").orElseThrow().headers()).containsEntry("Accept-Encoding", "gzip");
        assertThat(registry.of("austria")).isEmpty();
    }
}
