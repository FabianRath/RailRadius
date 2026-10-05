package com.railreach.service;

import com.railreach.dto.FeedDownloadResult;
import com.railreach.dto.FeedDownloadResult.Outcome;
import com.railreach.dto.FeedStatus;
import com.railreach.dto.FeedStatus.State;
import com.railreach.service.FeedRegistry.FeedSource;
import com.railreach.util.FeedFiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Keeps the GTFS zips current: how long each one runs, a daily log warning about outdated ones, and downloads of
 * new versions from {@code gtfs-feeds.txt}. A download does not touch the graph; the next reload picks it up.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FeedUpdateCenter {

    private static final Set<State> OUTDATED = Set.of(State.EXPIRED, State.EXPIRING);

    private final FeedRegistry registry;
    private final FeedDownloader downloader;
    private final GtfsLoadProgress loadProgress;

    @Value("${railreach.gtfs.directory:./gtfs}")
    private String gtfsDirectory;

    @Value("${railreach.feeds.expiring-days:30}")
    private int expiringDays;

    // Reading the calendar of a big feed takes seconds, so it is redone only when the zip changes
    private final Map<Path, ReadValidity> validityByZip = new ConcurrentHashMap<>();
    private final ReentrantLock downloadLock = new ReentrantLock();

    /** {@code validity} is null for a feed without a calendar. */
    private record ReadValidity(long lastModifiedMs, long sizeBytes, FeedValidity validity) {}

    public List<FeedStatus> statuses() {
        return statuses(LocalDate.now());
    }

    List<FeedStatus> statuses(LocalDate today) {
        return feedZips().stream().map(zip -> status(zip, today)).toList();
    }

    @Scheduled(fixedDelayString = "${railreach.feeds.check-interval:PT24H}")
    public void logOutdatedFeeds() {
        List<FeedStatus> statuses = statuses();
        List<FeedStatus> outdated = statuses.stream().filter(status -> OUTDATED.contains(status.getState())).toList();
        outdated.forEach(status -> log.warn("Feed {} {}: last service day {} ({} days left)", status.getSource(),
                status.getState(), status.getLastServiceDate(), status.getDaysLeft()));
        log.info("Feed check: {} feeds, {} expired or expiring", statuses.size(), outdated.size());
    }

    /** Empty if the source has no download URL. */
    public Optional<FeedDownloadResult> download(String source) {
        return registry.of(source).map(feed -> download(feed, LocalDate.now()));
    }

    /** Downloads every expired or expiring feed that has a download URL, one after the other. */
    public List<FeedDownloadResult> downloadOutdated() {
        LocalDate today = LocalDate.now();
        return statuses(today).stream()
                .filter(status -> OUTDATED.contains(status.getState()))
                .flatMap(status -> registry.of(status.getSource()).stream())
                .map(feed -> download(feed, today))
                .toList();
    }

    FeedDownloadResult download(FeedSource feed, LocalDate today) {
        // A reload reads the zips that a download replaces
        if (loadProgress.isRunning()) return busy(feed, "A GTFS reload is running");
        if (!downloadLock.tryLock()) return busy(feed, "Another feed download is running");
        try {
            return downloadLocked(feed, today);
        } finally {
            downloadLock.unlock();
        }
    }

    private FeedDownloadResult downloadLocked(FeedSource feed, LocalDate today) {
        Path target = directory().resolve(FeedFiles.zipName(feed.source()));
        FeedStatus before = Files.exists(target) ? status(target, today) : null;
        try {
            Optional<LocalDate> currentLastDay = Optional.ofNullable(before).map(FeedStatus::getLastServiceDate);
            Optional<String> rejection = downloader.replace(feed, target, currentLastDay, today);
            if (rejection.isPresent()) {
                log.warn("Feed {} not replaced: {}", feed.source(), rejection.get());
                return result(feed, Outcome.REJECTED, rejection.get(), before, null);
            }
            FeedStatus after = status(target, today);
            log.info("Feed {} replaced; last service day {}", feed.source(), after.getLastServiceDate());
            return result(feed, Outcome.REPLACED, "Downloaded. Reload GTFS to use it.", before, after);
        } catch (IOException e) {
            log.warn("Download of feed {} failed", feed.source(), e);
            return result(feed, Outcome.REJECTED, "Download failed: " + e, before, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(feed, Outcome.REJECTED, "Download interrupted", before, null);
        }
    }

    private List<Path> feedZips() {
        if (!Files.isDirectory(directory())) return List.of();
        try (Stream<Path> files = Files.list(directory())) {
            return files.filter(path -> FeedFiles.isZip(path.getFileName().toString())).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private FeedStatus status(Path zip, LocalDate today) {
        String fileName = zip.getFileName().toString();
        String source = FeedFiles.source(fileName);
        FeedStatus.FeedStatusBuilder status = FeedStatus.builder()
                .source(source)
                .fileName(fileName)
                .downloadUrl(registry.of(source).map(FeedSource::url).orElse(null));
        try {
            long sizeBytes = Files.size(zip);
            long lastModifiedMs = Files.getLastModifiedTime(zip).toMillis();
            status.sizeBytes(sizeBytes).downloadedAt(Instant.ofEpochMilli(lastModifiedMs));
            FeedValidity validity = validity(zip, lastModifiedMs, sizeBytes);
            if (validity == null) return status.state(State.NO_CALENDAR).build();
            return status.firstServiceDate(validity.firstDay())
                    .lastServiceDate(validity.lastDay())
                    .daysLeft(ChronoUnit.DAYS.between(today, validity.lastDay()))
                    .state(state(validity, today))
                    .build();
        } catch (IOException e) {
            log.warn("Cannot read feed {}", fileName, e);
            return status.state(State.UNREADABLE).build();
        }
    }

    private State state(FeedValidity validity, LocalDate today) {
        if (validity.lastDay().isBefore(today)) return State.EXPIRED;
        if (validity.firstDay().isAfter(today)) return State.NOT_YET_VALID;
        if (!validity.lastDay().isAfter(today.plusDays(expiringDays))) return State.EXPIRING;
        return State.OK;
    }

    private FeedValidity validity(Path zip, long lastModifiedMs, long sizeBytes) throws IOException {
        ReadValidity known = validityByZip.get(zip);
        if (known != null && known.lastModifiedMs() == lastModifiedMs && known.sizeBytes() == sizeBytes) {
            return known.validity();
        }
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            FeedValidity validity = FeedValidity.read(zipFile).orElse(null);
            validityByZip.put(zip, new ReadValidity(lastModifiedMs, sizeBytes, validity));
            return validity;
        }
    }

    private Path directory() {
        return Path.of(gtfsDirectory).toAbsolutePath();
    }

    private static FeedDownloadResult busy(FeedSource feed, String message) {
        return result(feed, Outcome.BUSY, message, null, null);
    }

    private static FeedDownloadResult result(FeedSource feed, Outcome outcome, String message, FeedStatus before,
                                             FeedStatus after) {
        return FeedDownloadResult.builder()
                .source(feed.source())
                .outcome(outcome)
                .message(message)
                .before(before)
                .after(after)
                .build();
    }
}
