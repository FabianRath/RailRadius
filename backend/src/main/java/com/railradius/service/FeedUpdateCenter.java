package com.railradius.service;

import com.railradius.dto.FeedDownloadResult;
import com.railradius.dto.FeedDownloadResult.Outcome;
import com.railradius.dto.FeedStatus;
import com.railradius.dto.FeedStatus.State;
import com.railradius.dto.FeedStorage;
import com.railradius.service.FeedDownloader.Replacement;
import com.railradius.service.FeedRegistry.FeedSource;
import com.railradius.util.FeedFiles;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
    private static final Set<State> TO_DOWNLOAD = Set.of(State.MISSING, State.EXPIRED, State.EXPIRING);
    private static final Comparator<FeedStatus> BY_STATE_AND_DAYS_LEFT = Comparator
            .comparing(FeedStatus::getState)
            .thenComparing(FeedStatus::getDaysLeft, Comparator.nullsLast(Comparator.naturalOrder()));

    private final FeedRegistry registry;
    private final FeedDownloader downloader;
    private final GtfsLoadProgress loadProgress;

    @Value("${railradius.gtfs.directory:./gtfs}")
    private String gtfsDirectory;

    @Value("${railradius.feeds.expiring-days:30}")
    private int expiringDays;

    // The feeds that belong in the GTFS directory; empty means every feed in gtfs-feeds.txt
    @Value("${railradius.feeds.sources:}")
    private List<String> sources;

    // Reading the calendar and trips of a big feed takes seconds, so it is redone only when the zip changes
    private final Map<Path, ReadTimespan> timespanByZip = new ConcurrentHashMap<>();
    private final ReentrantLock downloadLock = new ReentrantLock();

    /** {@code timespan} is null for a feed without a calendar. */
    private record ReadTimespan(long lastModifiedMs, long sizeBytes, FeedTimespan timespan) {}

    public List<FeedStatus> statuses() {
        return statuses(LocalDate.now());
    }

    /**
     * Every zip in the GTFS directory plus a {@link State#MISSING} row per expected feed without one. Sorted by
     * {@link State}, then by days left; feeds without days left stay in file name order.
     */
    List<FeedStatus> statuses(LocalDate today) {
        List<Path> zips = feedZips();
        Set<String> missing = new TreeSet<>(sources.isEmpty() ? registry.sources() : sources);
        zips.forEach(zip -> missing.remove(FeedFiles.source(zip.getFileName().toString())));
        return Stream.concat(missing.stream().map(this::missingStatus), zips.stream().map(zip -> status(zip, today)))
                .sorted(BY_STATE_AND_DAYS_LEFT)
                .toList();
    }

    public FeedStorage storage() {
        List<Path> inUse = feedZips();
        List<Path> previous = zipsIn(directory().resolve(FeedDownloader.PREVIOUS_DIRECTORY));
        return FeedStorage.builder()
                .inUseCount(inUse.size())
                .inUseBytes(totalBytes(inUse))
                .previousCount(previous.size())
                .previousBytes(totalBytes(previous))
                .build();
    }

    @Scheduled(fixedDelayString = "${railradius.feeds.check-interval:PT24H}")
    public void logOutdatedFeeds() {
        List<FeedStatus> statuses = statuses();
        List<FeedStatus> outdated = statuses.stream().filter(status -> OUTDATED.contains(status.getState())).toList();
        outdated.forEach(status -> log.warn("Feed {} {}: last service day {} ({} days left), ending early: {}",
                status.getSource(), status.getState(), status.getLastServiceDate(), status.getDaysLeft(),
                status.getEarlyEndingOperators()));
        List<String> missing = statuses.stream()
                .filter(status -> status.getState() == State.MISSING)
                .map(FeedStatus::getSource)
                .toList();
        if (!missing.isEmpty()) {
            log.warn("Feeds without a zip: {}. Download them in the admin panel (Feeds tab)", missing);
        }
        log.info("Feed check: {} feeds, {} missing, {} expired or expiring", statuses.size(), missing.size(),
                outdated.size());
    }

    /** Empty if the source has no download URL. */
    public Optional<FeedDownloadResult> download(String source) {
        return registry.of(source).map(feed -> download(feed, LocalDate.now()));
    }

    /** Downloads every missing, expired or expiring feed that has a download URL, one after the other. */
    public List<FeedDownloadResult> downloadOutdated() {
        LocalDate today = LocalDate.now();
        return statuses(today).stream()
                .filter(status -> TO_DOWNLOAD.contains(status.getState()))
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
            Replacement replacement = downloader.replace(feed, target, currentLastDay, today);
            if (!replacement.replaced()) {
                log.warn("Feed {} not replaced: {}", feed.source(), replacement.detail());
                return result(feed, Outcome.REJECTED, replacement.detail(), before, null);
            }
            FeedStatus after = status(target, today);
            log.info("Feed {} replaced from {}; last service day {}", feed.source(), replacement.detail(),
                    after.getLastServiceDate());
            String message = "Downloaded from " + replacement.detail() + ". Reload GTFS to use it.";
            return result(feed, Outcome.REPLACED, message, before, after);
        } catch (IOException e) {
            log.warn("Download of feed {} failed", feed.source(), e);
            return result(feed, Outcome.REJECTED, "Download failed: " + e, before, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(feed, Outcome.REJECTED, "Download interrupted", before, null);
        }
    }

    private List<Path> feedZips() {
        return zipsIn(directory());
    }

    private static List<Path> zipsIn(Path directory) {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> FeedFiles.isZip(path.getFileName().toString())).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long totalBytes(List<Path> zips) {
        long bytes = 0;
        for (Path zip : zips) {
            try {
                bytes += Files.size(zip);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return bytes;
    }

    private FeedStatus missingStatus(String source) {
        return statusBuilder(source, FeedFiles.zipName(source)).state(State.MISSING).build();
    }

    private FeedStatus status(Path zip, LocalDate today) {
        String fileName = zip.getFileName().toString();
        FeedStatus.FeedStatusBuilder status = statusBuilder(FeedFiles.source(fileName), fileName);
        try {
            long sizeBytes = Files.size(zip);
            long lastModifiedMs = Files.getLastModifiedTime(zip).toMillis();
            status.sizeBytes(sizeBytes).downloadedAt(Instant.ofEpochMilli(lastModifiedMs));
            FeedTimespan timespan = timespan(zip, lastModifiedMs, sizeBytes).timespan();
            if (timespan == null) return status.state(State.NO_CALENDAR).build();
            FeedValidity validity = timespan.validity();
            return status.firstServiceDate(validity.firstDay())
                    .lastServiceDate(validity.lastDay())
                    .daysLeft(ChronoUnit.DAYS.between(today, validity.lastDay()))
                    .earlyEndingOperators(timespan.earlyEndingOperators())
                    .state(state(timespan, today))
                    .build();
        } catch (IOException e) {
            log.warn("Cannot read feed {}", fileName, e);
            return status.state(State.UNREADABLE).build();
        }
    }

    private FeedStatus.FeedStatusBuilder statusBuilder(String source, String fileName) {
        return FeedStatus.builder()
                .source(source)
                .fileName(fileName)
                .earlyEndingOperators(List.of())
                .downloadUrl(registry.of(source).map(feed -> feed.urls().getFirst().url()).orElse(null));
    }

    /** A main operator that stops early makes the feed expiring, though its calendar runs on (B34). */
    private State state(FeedTimespan timespan, LocalDate today) {
        if (timespan.validity().lastDay().isBefore(today)) return State.EXPIRED;
        if (timespan.validity().firstDay().isAfter(today)) return State.NOT_YET_VALID;
        if (!timespan.trainsEnd().isAfter(today.plusDays(expiringDays))) return State.EXPIRING;
        return State.OK;
    }

    private ReadTimespan timespan(Path zip, long lastModifiedMs, long sizeBytes) throws IOException {
        ReadTimespan known = timespanByZip.get(zip);
        if (known != null && known.lastModifiedMs() == lastModifiedMs && known.sizeBytes() == sizeBytes) return known;
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            String source = FeedFiles.source(zip.getFileName().toString());
            FeedTimespan timespan = FeedTimespan.read(zipFile, source, ServiceCalendarReader.load(zipFile))
                    .orElse(null);
            ReadTimespan read = new ReadTimespan(lastModifiedMs, sizeBytes, timespan);
            timespanByZip.put(zip, read);
            return read;
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
