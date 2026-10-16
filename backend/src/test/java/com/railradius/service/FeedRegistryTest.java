package com.railradius.service;

import com.railradius.service.FeedRegistry.FeedSource;
import com.railradius.service.FeedRegistry.FeedUrl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedRegistryTest {

    @Test
    void lineWithHeadersParsesIntoUrlAndHeaders() {
        FeedSource feed = parseOne("finland;https://example.org/f.zip;Accept-Encoding: gzip|X-User: Rail");

        assertThat(feed.source()).isEqualTo("finland");
        assertThat(feed.urls()).containsExactly(new FeedUrl("https://example.org/f.zip",
                Map.of("Accept-Encoding", "gzip", "X-User", "Rail")));
    }

    @Test
    void lineWithoutHeadersHasNone() {
        assertThat(parseOne("germany;https://example.org/de").urls().getFirst().headers()).isEmpty();
    }

    @Test
    void lineWithoutUrlIsInvalid() {
        assertThatThrownBy(() -> parseOne("austria")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sourceOnSeveralLinesGetsFallbackUrlsInLineOrder() {
        Map<String, FeedSource> feeds = FeedRegistry.parseAll(List.of(
                "serbia;https://primary.example/rs.zip",
                "germany;https://example.org/de",
                "serbia;https://mirror.example/rs.zip;X-User: Rail"));

        assertThat(feeds.get("serbia").urls()).extracting(FeedUrl::url)
                .containsExactly("https://primary.example/rs.zip", "https://mirror.example/rs.zip");
        assertThat(feeds.get("germany").urls()).hasSize(1);
    }

    @Test
    void registryFileListsFeedsWithTheirHeaders() {
        FeedRegistry registry = new FeedRegistry();

        assertThat(registry.of("finland").orElseThrow().urls().getFirst().headers())
                .containsEntry("Accept-Encoding", "gzip");
        assertThat(registry.of("albania")).isEmpty();
    }

    private static FeedSource parseOne(String line) {
        return FeedRegistry.parseAll(List.of(line)).values().iterator().next();
    }
}
