package com.railreach.service;

import com.railreach.service.FeedRegistry.FeedSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Downloads a new version of a feed zip, checks it and swaps it in. The old zip is kept in {@code previous/}, which
 * the loader does not read. The download goes to a {@code .part} file first, so a reload never sees half a zip.
 */
@Component
@Slf4j
public class FeedDownloader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    // The largest feeds are ~500 MB
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(30);
    private static final int HTTP_OK = 200;
    private static final List<String> REQUIRED_FILES =
            List.of("stops.txt", "stop_times.txt", "trips.txt", "routes.txt");
    private static final String PART_SUFFIX = ".part";
    static final String PREVIOUS_DIRECTORY = "previous";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /**
     * Downloads {@code feed} and replaces {@code target} with it, unless the download is no usable feed, has expired
     * or ends before {@code currentLastDay}. Returns why it was rejected; {@code target} is then unchanged.
     */
    Optional<String> replace(FeedSource feed, Path target, Optional<LocalDate> currentLastDay, LocalDate today)
            throws IOException, InterruptedException {
        Path part = target.resolveSibling(target.getFileName() + PART_SUFFIX);
        try {
            Optional<String> rejection = download(feed, part);
            if (rejection.isEmpty()) rejection = problemWith(part, currentLastDay, today);
            if (rejection.isPresent()) return rejection;
            if (Files.exists(target)) {
                Path previous = Files.createDirectories(target.resolveSibling(PREVIOUS_DIRECTORY));
                Files.move(target, previous.resolve(target.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            return Optional.empty();
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private Optional<String> download(FeedSource feed, Path part) throws IOException, InterruptedException {
        log.info("Downloading feed {} from {}", feed.source(), feed.url());
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(feed.url())).timeout(DOWNLOAD_TIMEOUT);
        feed.headers().forEach(request::header);
        HttpResponse<InputStream> response =
                httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        // Some servers only answer when asked for gzip (Finland), and the client doesn't unpack it
        boolean gzipped = response.headers().firstValue("Content-Encoding")
                .filter("gzip"::equalsIgnoreCase)
                .isPresent();
        try (InputStream body = response.body()) {
            if (response.statusCode() != HTTP_OK) return Optional.of("HTTP " + response.statusCode());
            try (InputStream content = gzipped ? new GZIPInputStream(body) : body) {
                Files.copy(content, part, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> problemWith(Path part, Optional<LocalDate> currentLastDay, LocalDate today)
            throws IOException {
        Optional<FeedValidity> validity;
        try (ZipFile zipFile = new ZipFile(part.toFile())) {
            List<String> missing = REQUIRED_FILES.stream().filter(name -> zipFile.getEntry(name) == null).toList();
            if (!missing.isEmpty()) return Optional.of("Not a GTFS feed, missing " + String.join(", ", missing));
            validity = FeedValidity.read(zipFile);
        } catch (ZipException e) {
            return Optional.of("Not a zip file");
        }
        if (validity.isEmpty()) return Optional.empty();
        LocalDate lastDay = validity.get().lastDay();
        if (lastDay.isBefore(today)) return Optional.of("The download expired on " + lastDay);
        if (currentLastDay.isPresent() && lastDay.isBefore(currentLastDay.get())) {
            return Optional.of("The download ends on " + lastDay + ", before the current zip (" + currentLastDay.get()
                    + ")");
        }
        return Optional.empty();
    }
}
