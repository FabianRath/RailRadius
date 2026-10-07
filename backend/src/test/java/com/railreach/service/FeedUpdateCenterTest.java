package com.railreach.service;

import com.railreach.dto.FeedDownloadResult;
import com.railreach.dto.FeedDownloadResult.Outcome;
import com.railreach.dto.FeedStatus;
import com.railreach.dto.FeedStatus.State;
import com.railreach.dto.FeedStorage;
import com.railreach.service.FeedRegistry.FeedSource;
import com.railreach.service.FeedRegistry.FeedUrl;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FeedUpdateCenterTest {

    // zeta's services run from 2026-01-05 to 2026-03-29
    private static final LocalDate ZETA_FIRST_DAY = LocalDate.of(2026, 1, 5);
    private static final LocalDate ZETA_LAST_DAY = LocalDate.of(2026, 3, 29);
    private static final LocalDate DURING_ZETA = LocalDate.of(2026, 3, 8);
    private static final LocalDate AFTER_ZETA = LocalDate.of(2026, 10, 5);
    private static final LocalDate LATER_ZETA_LAST_DAY = LocalDate.of(2027, 3, 29);
    private static final int EXPIRING_DAYS = 30;
    private static final String FEED_PATH = "/feed.zip";
    private static final String MIRROR_PATH = "/mirror.zip";
    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final String AUTH_SETTING = "railreach.feeds.auth.zeta";
    private static final String AUTH_HEADER = "Basic ${" + AUTH_SETTING + "}";
    // Random bytes don't compress, so the padded zip is several times the size of plain zeta
    private static final int PADDING_BYTES = 200_000;

    @TempDir
    Path tempDir;

    private Path gtfsDir;
    private GtfsLoadProgress loadProgress;
    private MockEnvironment environment;
    private FeedUpdateCenter center;
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        gtfsDir = Files.createDirectories(tempDir.resolve("gtfs"));
        loadProgress = new GtfsLoadProgress();
        environment = new MockEnvironment();
        center = new FeedUpdateCenter(new FeedRegistry(), new FeedDownloader(environment), loadProgress);
        ReflectionTestUtils.setField(center, "gtfsDirectory", gtfsDir.toString());
        ReflectionTestUtils.setField(center, "expiringDays", EXPIRING_DAYS);
        expectSources("zeta");
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void feedPastItsLastServiceDayIsExpired() throws IOException {
        GtfsTestFixtures.zipFeed("zeta", gtfsDir.resolve("gtfs_zeta.zip"));

        FeedStatus status = center.statuses(AFTER_ZETA).getFirst();

        assertThat(status.getSource()).isEqualTo("zeta");
        assertThat(status.getState()).isEqualTo(State.EXPIRED);
        assertThat(status.getFirstServiceDate()).isEqualTo(ZETA_FIRST_DAY);
        assertThat(status.getLastServiceDate()).isEqualTo(ZETA_LAST_DAY);
        assertThat(status.getDaysLeft()).isNegative();
    }

    @Test
    void feedEndingWithinTheWarningWindowIsExpiring() throws IOException {
        GtfsTestFixtures.zipFeed("zeta", gtfsDir.resolve("gtfs_zeta.zip"));

        FeedStatus status = center.statuses(DURING_ZETA).getFirst();

        assertThat(status.getState()).isEqualTo(State.EXPIRING);
        assertThat(status.getDaysLeft()).isEqualTo(21);
    }

    @Test
    void feedEndingAfterTheWarningWindowIsOk() throws IOException {
        zipZetaEndingIn2027(gtfsDir.resolve("gtfs_zeta.zip"));

        assertThat(center.statuses(DURING_ZETA).getFirst().getState()).isEqualTo(State.OK);
    }

    @Test
    void feedBeforeItsFirstServiceDayIsNotYetValid() throws IOException {
        GtfsTestFixtures.zipFeed("zeta", gtfsDir.resolve("gtfs_zeta.zip"));

        assertThat(center.statuses(ZETA_FIRST_DAY.minusDays(1)).getFirst().getState())
                .isEqualTo(State.NOT_YET_VALID);
    }

    @Test
    void feedWithoutCalendarHasNoServiceDays() throws IOException {
        expectSources("alpha");
        GtfsTestFixtures.zipFeed("alpha", gtfsDir.resolve("gtfs_alpha.zip"));

        FeedStatus status = center.statuses(AFTER_ZETA).getFirst();

        assertThat(status.getState()).isEqualTo(State.NO_CALENDAR);
        assertThat(status.getLastServiceDate()).isNull();
        assertThat(status.getDaysLeft()).isNull();
    }

    @Test
    void fileThatIsNoZipIsUnreadable() throws IOException {
        expectSources("broken");
        Files.writeString(gtfsDir.resolve("gtfs_broken.zip"), "not a zip");

        assertThat(center.statuses(AFTER_ZETA).getFirst().getState()).isEqualTo(State.UNREADABLE);
    }

    @Test
    void feedMissingFromTheRegistryHasNoDownloadUrl() throws IOException {
        GtfsTestFixtures.zipFeed("zeta", gtfsDir.resolve("gtfs_zeta.zip"));

        assertThat(center.statuses(AFTER_ZETA).getFirst().getDownloadUrl()).isNull();
    }

    @Test
    void feedsAreSortedByStateThenByDaysLeft() throws IOException {
        expectSources("a", "missing");
        zipZeta(gtfsDir.resolve("gtfs_a.zip"), text -> text.replace("20260329", "20270329"));
        zipZeta(gtfsDir.resolve("gtfs_b.zip"), text -> text.replace("20260329", "20260920"));
        GtfsTestFixtures.zipFeed("zeta", gtfsDir.resolve("gtfs_c.zip"));
        zipZeta(gtfsDir.resolve("gtfs_d.zip"), text -> text.replace("20260329", "20261020"));
        GtfsTestFixtures.zipFeed("alpha", gtfsDir.resolve("gtfs_e.zip"));
        Files.writeString(gtfsDir.resolve("gtfs_f.zip"), "not a zip");
        GtfsTestFixtures.zipFeed("alpha", gtfsDir.resolve("gtfs_g.zip"));
        zipZeta(gtfsDir.resolve("gtfs_h.zip"),
                text -> text.replace("2026", "2027"));

        assertThat(center.statuses(AFTER_ZETA)).extracting(FeedStatus::getSource)
                .containsExactly("missing", "c", "b", "h", "e", "g", "f", "d", "a");
    }

    @Test
    void expectedFeedWithoutZipIsMissingAndDownloadable() throws IOException {
        expectSources("zeta", "austria");
        GtfsTestFixtures.zipFeed("zeta", gtfsDir.resolve("gtfs_zeta.zip"));

        FeedStatus missing = center.statuses(DURING_ZETA).getFirst();

        assertThat(missing.getSource()).isEqualTo("austria");
        assertThat(missing.getState()).isEqualTo(State.MISSING);
        assertThat(missing.getFileName()).isEqualTo("gtfs_austria.zip");
        assertThat(missing.getDownloadUrl()).isNotNull();
        assertThat(center.statuses(DURING_ZETA)).hasSize(2);
    }

    @Test
    void withoutConfiguredSourcesEveryRegisteredFeedIsExpected() {
        expectSources();

        assertThat(center.statuses(DURING_ZETA)).extracting(FeedStatus::getSource).contains("austria", "germany");
        assertThat(center.statuses(DURING_ZETA)).extracting(FeedStatus::getState).containsOnly(State.MISSING);
    }

    @Test
    void downloadCreatesAMissingGtfsDirectory() throws IOException {
        Path freshDir = tempDir.resolve("fresh-checkout").resolve("gtfs");
        ReflectionTestUtils.setField(center, "gtfsDirectory", freshDir.toString());
        serve(HTTP_OK, Files.readAllBytes(zipZetaEndingIn2027(tempDir.resolve("new.zip"))), false);

        FeedDownloadResult result = center.download(feed(), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REPLACED);
        assertThat(freshDir.resolve("gtfs_zeta.zip")).exists();
    }

    @Test
    void downloadReplacesTheFeedAndKeepsThePreviousZip() throws IOException {
        GtfsTestFixtures.zipFeed("zeta", gtfsDir.resolve("gtfs_zeta.zip"));
        serve(HTTP_OK, Files.readAllBytes(zipZetaEndingIn2027(tempDir.resolve("new.zip"))), false);

        FeedDownloadResult result = center.download(feed(), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REPLACED);
        assertThat(result.getMessage()).startsWith("Downloaded from localhost:");
        assertThat(result.getBefore().getLastServiceDate()).isEqualTo(ZETA_LAST_DAY);
        assertThat(result.getAfter().getLastServiceDate()).isEqualTo(LATER_ZETA_LAST_DAY);
        assertThat(center.statuses(DURING_ZETA).getFirst().getLastServiceDate()).isEqualTo(LATER_ZETA_LAST_DAY);
        assertThat(gtfsDir.resolve(FeedDownloader.PREVIOUS_DIRECTORY).resolve("gtfs_zeta.zip")).exists();
        assertNoPartFileLeft();
    }

    // B35
    @Test
    void downloadWithItsFilesInAFolderIsAccepted() throws IOException {
        Path served = tempDir.resolve("in-folder.zip");
        GtfsTestFixtures.zipFeedInFolder("zeta", served, "zeta_feed");
        serve(HTTP_OK, Files.readAllBytes(served), false);

        FeedDownloadResult result = center.download(feed(), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REPLACED);
        assertThat(result.getAfter().getLastServiceDate()).isEqualTo(ZETA_LAST_DAY);
    }

    @Test
    void storageCountsZipsInUseAndReplacedOnesSeparately() throws IOException {
        Path current = gtfsDir.resolve("gtfs_zeta.zip");
        GtfsTestFixtures.zipFeed("zeta", current);
        Path previous = Files.createDirectories(gtfsDir.resolve(FeedDownloader.PREVIOUS_DIRECTORY))
                .resolve("gtfs_old.zip");
        zipZetaEndingIn2027(previous);
        Files.writeString(gtfsDir.resolve("list.txt"), "not a zip");

        FeedStorage storage = center.storage();

        assertThat(storage.getInUseCount()).isEqualTo(1);
        assertThat(storage.getInUseBytes()).isEqualTo(Files.size(current));
        assertThat(storage.getPreviousCount()).isEqualTo(1);
        assertThat(storage.getPreviousBytes()).isEqualTo(Files.size(previous));
    }

    @Test
    void storageWithoutPreviousDirectoryIsZero() {
        FeedStorage storage = center.storage();

        assertThat(storage.getInUseCount()).isZero();
        assertThat(storage.getPreviousBytes()).isZero();
    }

    @Test
    void gzipEncodedDownloadIsUnpacked() throws IOException {
        serve(HTTP_OK, Files.readAllBytes(zipZetaEndingIn2027(tempDir.resolve("new.zip"))), true);

        FeedDownloadResult result = center.download(feed(), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REPLACED);
        assertThat(result.getBefore()).isNull();
        assertThat(result.getAfter().getLastServiceDate()).isEqualTo(LATER_ZETA_LAST_DAY);
    }

    @Test
    void downloadThatIsNoZipIsRejectedAndTheFeedKept() throws IOException {
        Path zip = gtfsDir.resolve("gtfs_zeta.zip");
        GtfsTestFixtures.zipFeed("zeta", zip);
        byte[] original = Files.readAllBytes(zip);
        serve(HTTP_OK, "<html>Moved</html>".getBytes(), false);

        FeedDownloadResult result = center.download(feed(), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.getMessage()).contains("Not a zip");
        assertThat(Files.readAllBytes(zip)).isEqualTo(original);
        assertNoPartFileLeft();
    }

    @Test
    void expiredDownloadIsRejected() throws IOException {
        Path served = tempDir.resolve("old.zip");
        GtfsTestFixtures.zipFeed("zeta", served);
        serve(HTTP_OK, Files.readAllBytes(served), false);

        FeedDownloadResult result = center.download(feed(), AFTER_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.getMessage()).contains("expired");
        assertThat(gtfsDir.resolve("gtfs_zeta.zip")).doesNotExist();
        assertNoPartFileLeft();
    }

    @Test
    void downloadEndingBeforeTheCurrentFeedIsRejected() throws IOException {
        zipZetaEndingIn2027(gtfsDir.resolve("gtfs_zeta.zip"));
        Path served = tempDir.resolve("old.zip");
        GtfsTestFixtures.zipFeed("zeta", served);
        serve(HTTP_OK, Files.readAllBytes(served), false);

        FeedDownloadResult result = center.download(feed(), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.getMessage()).contains("before the current");
        assertThat(center.statuses(DURING_ZETA).getFirst().getLastServiceDate()).isEqualTo(LATER_ZETA_LAST_DAY);
    }

    @Test
    void downloadWithoutServiceDaysIsRejected() throws IOException {
        Path served = tempDir.resolve("alpha.zip");
        GtfsTestFixtures.zipFeed("alpha", served);
        serve(HTTP_OK, Files.readAllBytes(served), false);

        FeedDownloadResult result = center.download(feed(), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.getMessage()).contains("No service days");
        assertThat(gtfsDir.resolve("gtfs_zeta.zip")).doesNotExist();
    }

    @Test
    void downloadWithoutRailRoutesIsRejected() throws IOException {
        Path served = zipZeta(tempDir.resolve("bus.zip"), text -> text.replace("Zeta Regional,2", "Zeta Regional,3"));
        serve(HTTP_OK, Files.readAllBytes(served), false);

        FeedDownloadResult result = center.download(feed(), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.getMessage()).contains("No rail routes");
    }

    @Test
    void downloadMuchSmallerThanTheCurrentZipIsRejected() throws IOException {
        Path zip = gtfsDir.resolve("gtfs_zeta.zip");
        zipZeta(zip, UnaryOperator.identity(), PADDING_BYTES);
        byte[] original = Files.readAllBytes(zip);
        serve(HTTP_OK, Files.readAllBytes(zipZetaEndingIn2027(tempDir.resolve("new.zip"))), false);

        FeedDownloadResult result = center.download(feed(), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.getMessage()).contains("of the current zip's size");
        assertThat(Files.readAllBytes(zip)).isEqualTo(original);
    }

    @Test
    void downloadStartingAfterTodayIsRejected() throws IOException {
        Path served = tempDir.resolve("zeta.zip");
        GtfsTestFixtures.zipFeed("zeta", served);
        serve(HTTP_OK, Files.readAllBytes(served), false);

        FeedDownloadResult result = center.download(feed(), ZETA_FIRST_DAY.minusDays(1));

        assertThat(result.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.getMessage()).isEqualTo("The download starts on " + ZETA_FIRST_DAY);
    }

    @Test
    void httpErrorIsRejected() throws IOException {
        serve(HTTP_NOT_FOUND, "missing".getBytes(), false);

        FeedDownloadResult result = center.download(feed(), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.getMessage()).isEqualTo("HTTP 404");
        assertNoPartFileLeft();
    }

    @Test
    void failedUrlFallsBackToTheNextOne() throws IOException {
        serve(FEED_PATH, HTTP_NOT_FOUND, "missing".getBytes(), false);
        serve(MIRROR_PATH, HTTP_OK, Files.readAllBytes(zipZetaEndingIn2027(tempDir.resolve("new.zip"))), false);

        FeedDownloadResult result = center.download(feed(FEED_PATH, MIRROR_PATH), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REPLACED);
        assertThat(result.getAfter().getLastServiceDate()).isEqualTo(LATER_ZETA_LAST_DAY);
    }

    @Test
    void rejectedDownloadFallsBackToTheNextUrl() throws IOException {
        serve(FEED_PATH, HTTP_OK, "<html>Moved</html>".getBytes(), false);
        serve(MIRROR_PATH, HTTP_OK, Files.readAllBytes(zipZetaEndingIn2027(tempDir.resolve("new.zip"))), false);

        FeedDownloadResult result = center.download(feed(FEED_PATH, MIRROR_PATH), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REPLACED);
        assertNoPartFileLeft();
    }

    @Test
    void feedWhoseUrlsAllFailIsRejectedWithEveryReason() throws IOException {
        Path zip = gtfsDir.resolve("gtfs_zeta.zip");
        GtfsTestFixtures.zipFeed("zeta", zip);
        byte[] original = Files.readAllBytes(zip);
        serve(FEED_PATH, HTTP_NOT_FOUND, "missing".getBytes(), false);
        serve(MIRROR_PATH, HTTP_OK, "<html>Moved</html>".getBytes(), false);

        FeedDownloadResult result = center.download(feed(FEED_PATH, MIRROR_PATH), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.getMessage()).contains("HTTP 404", "Not a zip");
        assertThat(Files.readAllBytes(zip)).isEqualTo(original);
        assertNoPartFileLeft();
    }

    @Test
    void headerSettingIsFilledInFromTheEnvironment() throws IOException {
        environment.setProperty(AUTH_SETTING, "c2VjcmV0");
        byte[] zip = Files.readAllBytes(zipZetaEndingIn2027(tempDir.resolve("new.zip")));
        server.createContext(FEED_PATH, exchange -> {
            boolean authorized = "Basic c2VjcmV0".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] content = authorized ? zip : new byte[0];
            exchange.sendResponseHeaders(authorized ? HTTP_OK : HTTP_UNAUTHORIZED, content.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(content);
            }
        });

        FeedDownloadResult result = center.download(feedWithAuthHeader(), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REPLACED);
    }

    @Test
    void headerWithAnUnsetSettingIsRejectedAndNamesIt() throws IOException {
        serve(HTTP_OK, Files.readAllBytes(zipZetaEndingIn2027(tempDir.resolve("new.zip"))), false);

        FeedDownloadResult result = center.download(feedWithAuthHeader(), DURING_ZETA);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.getMessage()).contains(AUTH_SETTING);
    }

    @Test
    void downloadDuringAGtfsReloadIsRefused() {
        loadProgress.start();

        assertThat(center.download(feed(), DURING_ZETA).getOutcome()).isEqualTo(Outcome.BUSY);
    }

    private void expectSources(String... sources) {
        ReflectionTestUtils.setField(center, "sources", List.of(sources));
    }

    private FeedSource feed() {
        return feed(FEED_PATH);
    }

    private FeedSource feed(String... paths) {
        String base = "http://localhost:" + server.getAddress().getPort();
        return new FeedSource("zeta", Stream.of(paths).map(path -> new FeedUrl(base + path, Map.of())).toList());
    }

    private FeedSource feedWithAuthHeader() {
        String url = "http://localhost:" + server.getAddress().getPort() + FEED_PATH;
        return new FeedSource("zeta", List.of(new FeedUrl(url, Map.of("Authorization", AUTH_HEADER))));
    }

    private void serve(int statusCode, byte[] body, boolean gzip) {
        serve(FEED_PATH, statusCode, body, gzip);
    }

    private void serve(String path, int statusCode, byte[] body, boolean gzip) {
        server.createContext(path, exchange -> {
            byte[] content = gzip ? gzipped(body) : body;
            if (gzip) exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(statusCode, content.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(content);
            }
        });
    }

    private static byte[] gzipped(byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(body);
        }
        return bytes.toByteArray();
    }

    /** zeta with its weekly services running a year longer. */
    private Path zipZetaEndingIn2027(Path zipFile) throws IOException {
        return zipZeta(zipFile, text -> text.replace("20260329", "20270329"));
    }

    private Path zipZeta(Path zipFile, UnaryOperator<String> edit) throws IOException {
        return zipZeta(zipFile, edit, 0);
    }

    /** zeta with {@code edit} applied to every file, plus {@code paddingBytes} of random data as shapes.txt. */
    private Path zipZeta(Path zipFile, UnaryOperator<String> edit, int paddingBytes) throws IOException {
        Path folder = Files.createTempDirectory(tempDir, "zeta");
        try (Stream<Path> files = Files.list(GtfsTestFixtures.resource("gtfs-mini/zeta"))) {
            for (Path file : files.toList()) {
                Files.writeString(folder.resolve(file.getFileName()), edit.apply(Files.readString(file)));
            }
        }
        if (paddingBytes > 0) {
            byte[] padding = new byte[paddingBytes];
            new Random(0).nextBytes(padding);
            Files.write(folder.resolve("shapes.txt"), padding);
        }
        GtfsTestFixtures.zipFolder(folder, zipFile);
        return zipFile;
    }

    private void assertNoPartFileLeft() throws IOException {
        try (Stream<Path> files = Files.list(gtfsDir)) {
            assertThat(files.map(Path::toString)).noneMatch(name -> name.endsWith(".part"));
        }
    }
}
