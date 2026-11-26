package com.railradius.service;

import com.railradius.util.ResourceLines;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Where each feed is downloaded from, as listed in the local {@code gtfs-feeds.txt}. The file is kept out of git so
 * the download URLs aren't published; without it no feed has a download URL.
 */
@Slf4j
@Component
public class FeedRegistry {

    private static final int MIN_FIELDS = 2;
    private static final int MAX_FIELDS = 3;
    private static final String HEADER_SEPARATOR = "\\|";
    private static final String HEADER_NAME_SEPARATOR = ":";

    /** One download location; {@code headers} are sent with the request. */
    record FeedUrl(String url, Map<String, String> headers) {}

    /** One registered feed; {@code urls} in the order they are tried, never empty. */
    record FeedSource(String source, List<FeedUrl> urls) {}

    private record FeedLine(String source, FeedUrl url) {}

    private final Map<String, FeedSource> feeds;

    public FeedRegistry(@Value("${railradius.feeds.registry-file:config/gtfs-feeds.txt}") String registryFile) {
        Path file = Path.of(registryFile);
        if (Files.isRegularFile(file)) {
            feeds = parseAll(ResourceLines.read(file));
            log.info("Download URLs of {} feeds from {}", feeds.size(), file.toAbsolutePath());
        } else {
            feeds = Map.of();
            log.info("No feed list at {}: the update center has no download URLs", file.toAbsolutePath());
        }
    }

    Optional<FeedSource> of(String source) {
        return Optional.ofNullable(feeds.get(source));
    }

    Set<String> sources() {
        return feeds.keySet();
    }

    /** A source on several lines gets their URLs as fallbacks, in line order. */
    static Map<String, FeedSource> parseAll(List<String> lines) {
        Map<String, List<FeedUrl>> urlsBySource = lines.stream()
                .map(FeedRegistry::parse)
                .collect(Collectors.groupingBy(FeedLine::source, LinkedHashMap::new,
                        Collectors.mapping(FeedLine::url, Collectors.toUnmodifiableList())));
        return urlsBySource.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                entry -> new FeedSource(entry.getKey(), entry.getValue())));
    }

    // Format: source;url[;Header: value|Header: value]
    private static FeedLine parse(String line) {
        List<String> fields = Arrays.stream(line.split(";")).map(String::strip).toList();
        if (fields.size() < MIN_FIELDS || fields.size() > MAX_FIELDS) {
            throw new IllegalStateException("Invalid feed line: " + line);
        }
        Map<String, String> headers = fields.size() < MAX_FIELDS ? Map.of() : headers(fields.get(2), line);
        return new FeedLine(fields.get(0), new FeedUrl(fields.get(1), headers));
    }

    private static Map<String, String> headers(String text, String line) {
        Map<String, String> headers = new HashMap<>();
        for (String header : text.split(HEADER_SEPARATOR)) {
            String[] nameAndValue = header.split(HEADER_NAME_SEPARATOR, 2);
            if (nameAndValue.length != 2) throw new IllegalStateException("Invalid header in feed line: " + line);
            headers.put(nameAndValue[0].strip(), nameAndValue[1].strip());
        }
        return Map.copyOf(headers);
    }
}
