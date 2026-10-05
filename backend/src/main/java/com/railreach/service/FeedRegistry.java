package com.railreach.service;

import com.railreach.util.ResourceLines;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Where each feed is downloaded from, as listed in {@code gtfs-feeds.txt}. */
@Component
public class FeedRegistry {

    private static final String RESOURCE = "/gtfs-feeds.txt";
    private static final int MIN_FIELDS = 2;
    private static final int MAX_FIELDS = 3;
    private static final String HEADER_SEPARATOR = "\\|";
    private static final String HEADER_NAME_SEPARATOR = ":";

    /** One registered feed; {@code headers} are sent with the download request. */
    record FeedSource(String source, String url, Map<String, String> headers) {}

    private final Map<String, FeedSource> feeds = ResourceLines.read(RESOURCE).stream()
            .map(FeedRegistry::parse)
            .collect(Collectors.toUnmodifiableMap(FeedSource::source, Function.identity()));

    Optional<FeedSource> of(String source) {
        return Optional.ofNullable(feeds.get(source));
    }

    // Format: source;url[;Header: value|Header: value]
    static FeedSource parse(String line) {
        List<String> fields = Arrays.stream(line.split(";")).map(String::strip).toList();
        if (fields.size() < MIN_FIELDS || fields.size() > MAX_FIELDS) {
            throw new IllegalStateException("Invalid feed line: " + line);
        }
        Map<String, String> headers = fields.size() < MAX_FIELDS ? Map.of() : headers(fields.get(2), line);
        return new FeedSource(fields.get(0), fields.get(1), headers);
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
