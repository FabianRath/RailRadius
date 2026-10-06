package com.railreach.service;

import com.railreach.service.FeedRegistry.FeedSource;
import com.railreach.service.FeedRegistry.FeedUrl;
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
import java.util.ArrayList;
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
    // Real updates grow or shrink a little; a much smaller zip is usually a truncated export or a different feed
    private static final double MIN_SIZE_RATIO_TO_CURRENT = 0.5;
    private static final int PERCENT = 100;
    private static final String PART_SUFFIX = ".part";
    static final String PREVIOUS_DIRECTORY = "previous";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /** {@code detail} is the host the new zip came from, or why every URL was rejected. */
    record Replacement(boolean replaced, String detail) {}

    /**
     * Downloads {@code feed} and replaces {@code target} with it, unless the download is no usable rail feed, is less
     * than half the size of {@code target}, does not run today or ends before {@code currentLastDay}. A failed or
     * rejected URL falls back to the feed's next one; if all fail, {@code target} is unchanged.
     */
    Replacement replace(FeedSource feed, Path target, Optional<LocalDate> currentLastDay, LocalDate today)
            throws IOException, InterruptedException {
        // A fresh checkout has no GTFS directory yet
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + PART_SUFFIX);
        List<String> rejections = new ArrayList<>();
        try {
            for (FeedUrl url : feed.urls()) {
                Optional<String> rejection = tryUrl(feed.source(), url, part, target, currentLastDay, today);
                if (rejection.isEmpty()) {
                    swapIn(part, target);
                    return new Replacement(true, host(url));
                }
                log.warn("Feed {} from {} rejected: {}", feed.source(), url.url(), rejection.get());
                rejections.add(feed.urls().size() == 1 ? rejection.get() : host(url) + ": " + rejection.get());
            }
            return new Replacement(false, String.join("; ", rejections));
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private Optional<String> tryUrl(String source, FeedUrl url, Path part, Path target,
            Optional<LocalDate> currentLastDay, LocalDate today) throws InterruptedException {
        try {
            Optional<String> rejection = download(source, url, part);
            return rejection.isPresent() ? rejection : problemWith(part, target, currentLastDay, today);
        } catch (IOException e) {
            log.warn("Download of feed {} from {} failed", source, url.url(), e);
            return Optional.of("Download failed: " + e);
        }
    }

    private static void swapIn(Path part, Path target) throws IOException {
        if (Files.exists(target)) {
            Path previous = Files.createDirectories(target.resolveSibling(PREVIOUS_DIRECTORY));
            Files.move(target, previous.resolve(target.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private Optional<String> download(String source, FeedUrl url, Path part) throws IOException, InterruptedException {
        log.info("Downloading feed {} from {}", source, url.url());
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url.url())).timeout(DOWNLOAD_TIMEOUT);
        url.headers().forEach(request::header);
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

    private static String host(FeedUrl url) {
        return URI.create(url.url()).getAuthority();
    }

    private static Optional<String> problemWith(Path part, Path target, Optional<LocalDate> currentLastDay,
            LocalDate today) throws IOException {
        FeedValidity validity;
        try (ZipFile zipFile = new ZipFile(part.toFile())) {
            List<String> missing = REQUIRED_FILES.stream().filter(name -> zipFile.getEntry(name) == null).toList();
            if (!missing.isEmpty()) return Optional.of("Not a GTFS feed, missing " + String.join(", ", missing));
            if (!RailTripReader.hasRailRoutes(zipFile)) return Optional.of("No rail routes in routes.txt");
            Optional<FeedValidity> read = FeedValidity.read(zipFile);
            if (read.isEmpty()) return Optional.of("No service days in calendar.txt or calendar_dates.txt");
            validity = read.get();
        } catch (ZipException e) {
            return Optional.of("Not a zip file");
        }
        Optional<String> sizeProblem = sizeProblem(part, target);
        return sizeProblem.isPresent() ? sizeProblem : datesProblem(validity, currentLastDay, today);
    }

    private static Optional<String> sizeProblem(Path part, Path target) throws IOException {
        if (!Files.exists(target)) return Optional.empty();
        long partBytes = Files.size(part);
        long targetBytes = Files.size(target);
        if (partBytes >= targetBytes * MIN_SIZE_RATIO_TO_CURRENT) return Optional.empty();
        return Optional.of("The download is only " + partBytes * PERCENT / targetBytes + "% of the current zip's size");
    }

    private static Optional<String> datesProblem(FeedValidity validity, Optional<LocalDate> currentLastDay,
            LocalDate today) {
        if (validity.lastDay().isBefore(today)) return Optional.of("The download expired on " + validity.lastDay());
        if (validity.firstDay().isAfter(today)) return Optional.of("The download starts on " + validity.firstDay());
        if (currentLastDay.isPresent() && validity.lastDay().isBefore(currentLastDay.get())) {
            return Optional.of("The download ends on " + validity.lastDay() + ", before the current zip ("
                    + currentLastDay.get() + ")");
        }
        return Optional.empty();
    }
}
