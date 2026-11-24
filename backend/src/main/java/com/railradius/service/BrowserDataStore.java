package com.railradius.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * The gzipped {@link BrowserDataFile} of the served graph (#9), built after each serve off the caller's thread. Its
 * version is a hash of the gzipped file, so it changes exactly when the data or the format does and a browser can
 * cache it for good. It is published only while the graph it was built from is still served: after a reload, nothing
 * is published until the new graph's file is ready, so a browser never searches on data the server no longer has.
 * A separate bean so that {@code @Async} goes through Spring's proxy.
 */
@Component
@Slf4j
public class BrowserDataStore {

    private static final String FILE_PREFIX = "browser-data-";
    private static final String FILE_SUFFIX = ".bin.gz";
    private static final String PART_SUFFIX = ".part";
    private static final String HASH_ALGORITHM = "SHA-256";
    // 64 bits: a version names one of a handful of files, it doesn't have to resist forgery
    private static final int VERSION_BYTES = 8;

    /** A built file: its version, where it is and its gzipped size. */
    public record Published(String version, Path file, long sizeBytes) {}

    private record Built(ServedGraph source, Published published) {}

    private final TransitGraph graph;
    private final Path directory;
    // Null until the first build has finished
    private volatile Built built;

    BrowserDataStore(TransitGraph graph,
                     @Value("${railradius.browser-data.directory:data/browser-data}") String directory) {
        this.graph = graph;
        this.directory = Path.of(directory).toAbsolutePath();
    }

    /** Builds the file of the graph served now and publishes it, unless another graph is served by then. */
    @Async
    public void buildServed() {
        ServedGraph served = graph.served();
        long startMs = System.currentTimeMillis();
        try {
            Published published = write(served);
            if (graph.served() != served) {
                log.info("Browser data {} not published: another graph is served by now", published.version());
                Files.deleteIfExists(published.file());
                return;
            }
            built = new Built(served, published);
            deleteOthers(published.file());
            log.info("Browser data {} built in {} ms ({})", published.version(), System.currentTimeMillis() - startMs,
                    String.format("%.1f MB gzipped", published.sizeBytes() / 1e6));
        } catch (IOException | RuntimeException e) {
            log.error("Failed to build the browser data in {}", directory, e);
        }
    }

    /** The file of the graph served now; empty while it is built, or if its build failed. */
    public Optional<Published> current() {
        Built now = built;
        return now != null && now.source() == graph.served() ? Optional.of(now.published()) : Optional.empty();
    }

    private Published write(ServedGraph served) throws IOException {
        Files.createDirectories(directory);
        Path raw = Files.createTempFile(directory, FILE_PREFIX, PART_SUFFIX);
        Path gzipped = Files.createTempFile(directory, FILE_PREFIX, FILE_SUFFIX + PART_SUFFIX);
        try {
            BrowserDataFile.write(served, raw);
            byte[] hash = gzip(raw, gzipped);
            String version = HexFormat.of().formatHex(Arrays.copyOf(hash, VERSION_BYTES));
            Path file = directory.resolve(FILE_PREFIX + version + FILE_SUFFIX);
            Files.move(gzipped, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new Published(version, file, Files.size(file));
        } finally {
            Files.deleteIfExists(raw);
            Files.deleteIfExists(gzipped);
        }
    }

    /** Gzips from into to and returns the hash of the gzipped bytes. */
    private static byte[] gzip(Path from, Path to) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(from);
             OutputStream out = new BestGzipOutputStream(new DigestOutputStream(Files.newOutputStream(to), digest))) {
            in.transferTo(out);
        }
        return digest.digest();
    }

    /** Deletes the files of earlier graphs. A file a client is still downloading may stay until the next build. */
    private void deleteOthers(Path keep) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path file : files) {
                if (file.equals(keep)) continue;
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    log.debug("Could not delete {} yet", file, e);
                }
            }
        } catch (IOException e) {
            log.warn("Could not delete old browser data in {}", directory, e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has " + HASH_ALGORITHM, e);
        }
    }

    // The file is built once per load and fetched by every browser, so the slowest, smallest level pays off
    private static final class BestGzipOutputStream extends GZIPOutputStream {

        BestGzipOutputStream(OutputStream out) throws IOException {
            super(out);
            def.setLevel(Deflater.BEST_COMPRESSION);
        }
    }
}
