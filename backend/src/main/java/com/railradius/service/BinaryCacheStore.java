package com.railradius.service;

import com.railradius.dto.OperatorEnd;
import com.railradius.model.Station;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and writes what the app serves as one {@link BinaryBlocks} file (S20): stations, their translated names, the
 * {@link TripInfo} of each store trip, how long each feed runs ({@link FeedTimespan}, F29) and the
 * {@link TimetableStore}. Reading it takes well under a second, where
 * parsing the zips takes minutes. The dev machine writes it after each load of the zips; its hash is that of the zips
 * it was built from, so a server's file can be traced to them.
 * <p>
 * After a string table, the blocks are: stations (ID, name, parent station, country as string indexes; latitude,
 * longitude), translated names (stop, then its names as CSR), the trip infos (train number, headsign, operator by
 * store trip), the feed timespans (source, first and last day as epoch days, then the early-ending operators as CSR:
 * name and last day), and the store ({@link TimetableStore#writeTo}). A missing string is {@link #NO_STRING}, a
 * missing coordinate NaN.
 */
@Component
@Slf4j
public class BinaryCacheStore {

    /** Bump on every change of the layout or of how the store is built: a file with another version is not read. */
    static final int FORMAT_VERSION = 2;

    private static final int NO_STRING = -1;
    private static final long BYTES_PER_MB = 1024 * 1024;
    private static final String PART_SUFFIX = ".part";
    private static final String HASH_ALGORITHM = "SHA-256";

    private final Path file;

    BinaryCacheStore(@Value("${railradius.gtfs.store-file:data/gtfs-store.bin}") String storeFile) {
        this.file = Path.of(storeFile).toAbsolutePath();
    }

    /** What the file holds. tripInfos.get(i) belongs to the store's trip i; feeds are sorted by source. */
    record BinaryCacheData(List<Station> stations, Map<String, Set<String>> translatedNames, List<TripInfo> tripInfos,
                           List<FeedTimespan> feeds, TimetableStore store) {}

    /** Strings by index, each stored once. */
    private static final class StringTable {

        private final Map<String, Integer> indexes = new HashMap<>();
        private final List<String> strings = new ArrayList<>();

        int index(String value) {
            if (value == null) return NO_STRING;
            return indexes.computeIfAbsent(value, k -> {
                strings.add(k);
                return strings.size() - 1;
            });
        }
    }

    /** Empty if the file is missing, and with a WARN if it has another format version or can't be read. */
    Optional<BinaryCacheData> load() {
        if (!Files.exists(file)) {
            log.info("No binary cache file at {}", file);
            return Optional.empty();
        }
        long startMs = System.currentTimeMillis();
        try (BinaryBlocks.Reader in = new BinaryBlocks.Reader(file, FORMAT_VERSION)) {
            BinaryCacheData data = read(in);
            in.checkAllRead();
            log.info("✓ Binary cache read in {} ms ({} MB, built from the zips with {} {})",
                    System.currentTimeMillis() - startMs, Files.size(file) / BYTES_PER_MB, HASH_ALGORITHM,
                    HexFormat.of().formatHex(in.hash()));
            return Optional.of(data);
        } catch (Exception e) {
            log.warn("Binary cache {} is not usable", file, e);
            return Optional.empty();
        }
    }

    /** Writes the data, hashed with inputFiles, the zips it comes from (in this order). Logs a failure. */
    void save(BinaryCacheData data, List<Path> inputFiles) {
        Path part = file.resolveSibling(file.getFileName() + PART_SUFFIX);
        long startMs = System.currentTimeMillis();
        try {
            Files.createDirectories(file.getParent());
            try (BinaryBlocks.Writer out = new BinaryBlocks.Writer(part, FORMAT_VERSION, hash(inputFiles))) {
                write(data, out);
            }
            // A server never sees a half-written file
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("✓ Binary cache saved to {} in {} ms ({} MB)", file, System.currentTimeMillis() - startMs,
                    Files.size(file) / BYTES_PER_MB);
        } catch (Exception e) {
            log.error("❌ FAILED to save the binary cache to {}", file, e);
        }
    }

    void delete() {
        try {
            if (Files.deleteIfExists(file)) log.info("✓ Binary cache deleted");
        } catch (IOException e) {
            log.error("❌ FAILED to delete the binary cache {}", file, e);
        }
    }

    String path() {
        return file.toString();
    }

    boolean exists() {
        return Files.exists(file);
    }

    /** 0 if the file is missing. */
    long sizeMb() {
        return file.toFile().length() / BYTES_PER_MB;
    }

    private static void write(BinaryCacheData data, BinaryBlocks.Writer out) throws IOException {
        StringTable strings = new StringTable();
        int[][] stationColumns = stationColumns(data.stations(), strings);
        int[][] translatedNameColumns = translatedNameColumns(data.translatedNames(), strings);
        int[][] tripInfoColumns = tripInfoColumns(data.tripInfos(), strings);
        int[][] feedColumns = feedColumns(data.feeds(), strings);
        PackedStrings.of(strings.strings).writeTo(out);
        for (int[] column : stationColumns) {
            out.ints(column);
        }
        out.longs(data.stations().stream().mapToLong(station -> coordinateBits(station.getLatitude())).toArray());
        out.longs(data.stations().stream().mapToLong(station -> coordinateBits(station.getLongitude())).toArray());
        for (int[] column : translatedNameColumns) {
            out.ints(column);
        }
        for (int[] column : tripInfoColumns) {
            out.ints(column);
        }
        for (int[] column : feedColumns) {
            out.ints(column);
        }
        data.store().writeTo(out);
    }

    private static BinaryCacheData read(BinaryBlocks.Reader in) throws IOException {
        PackedStrings packed = PackedStrings.readFrom(in);
        // Decoded once, so equal strings share one instance, like the graph's station IDs (S16)
        String[] strings = new String[packed.size()];
        for (int i = 0; i < strings.length; i++) {
            strings[i] = packed.get(i);
        }
        return new BinaryCacheData(readStations(in, strings), readTranslatedNames(in, strings),
                readTripInfos(in, strings), readFeeds(in, strings), TimetableStore.readFrom(in));
    }

    /** ID, name, parent station and country of each station. */
    private static int[][] stationColumns(List<Station> stations, StringTable strings) {
        int[][] columns = new int[4][stations.size()];
        for (int i = 0; i < stations.size(); i++) {
            Station station = stations.get(i);
            columns[0][i] = strings.index(station.getId());
            columns[1][i] = strings.index(station.getName());
            columns[2][i] = strings.index(station.getParentStation());
            columns[3][i] = strings.index(station.getCountry());
        }
        return columns;
    }

    private static List<Station> readStations(BinaryBlocks.Reader in, String[] strings) throws IOException {
        int[] ids = in.ints();
        int[] names = in.ints();
        int[] parents = in.ints();
        int[] countries = in.ints();
        long[] latitudes = in.longs();
        long[] longitudes = in.longs();
        List<Station> stations = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            stations.add(new Station(string(strings, ids[i]), string(strings, names[i]), coordinate(latitudes[i]),
                    coordinate(longitudes[i]), string(strings, parents[i]), string(strings, countries[i])));
        }
        return stations;
    }

    /** The stops, and their names as CSR. */
    private static int[][] translatedNameColumns(Map<String, Set<String>> translatedNames, StringTable strings) {
        int[] stops = new int[translatedNames.size()];
        int[] nameStart = new int[stops.length + 1];
        IntList names = new IntList();
        int stop = 0;
        for (Map.Entry<String, Set<String>> entry : translatedNames.entrySet()) {
            stops[stop] = strings.index(entry.getKey());
            entry.getValue().forEach(name -> names.add(strings.index(name)));
            nameStart[++stop] = names.size();
        }
        return new int[][] {stops, nameStart, names.toArray()};
    }

    private static Map<String, Set<String>> readTranslatedNames(BinaryBlocks.Reader in, String[] strings)
            throws IOException {
        int[] stops = in.ints();
        int[] nameStart = in.ints();
        int[] names = in.ints();
        Map<String, Set<String>> translatedNames = new HashMap<>(stops.length * 2);
        for (int stop = 0; stop < stops.length; stop++) {
            Set<String> stopNames = new HashSet<>();
            for (int i = nameStart[stop]; i < nameStart[stop + 1]; i++) {
                stopNames.add(strings[names[i]]);
            }
            translatedNames.put(strings[stops[stop]], stopNames);
        }
        return translatedNames;
    }

    /** Train number, headsign and operator of each store trip. */
    private static int[][] tripInfoColumns(List<TripInfo> tripInfos, StringTable strings) {
        int[][] columns = new int[3][tripInfos.size()];
        for (int trip = 0; trip < tripInfos.size(); trip++) {
            TripInfo info = tripInfos.get(trip);
            columns[0][trip] = strings.index(info.trainNumber());
            columns[1][trip] = strings.index(info.headsign());
            columns[2][trip] = strings.index(info.operator());
        }
        return columns;
    }

    private static List<TripInfo> readTripInfos(BinaryBlocks.Reader in, String[] strings) throws IOException {
        int[] trainNumbers = in.ints();
        int[] headsigns = in.ints();
        int[] operators = in.ints();
        List<TripInfo> tripInfos = new ArrayList<>(trainNumbers.length);
        for (int trip = 0; trip < trainNumbers.length; trip++) {
            tripInfos.add(new TripInfo(string(strings, trainNumbers[trip]), string(strings, headsigns[trip]),
                    string(strings, operators[trip])));
        }
        return tripInfos;
    }

    /** Source, first and last day of each feed, then its early-ending operators (name, last day) as CSR. */
    private static int[][] feedColumns(List<FeedTimespan> feeds, StringTable strings) {
        int[] sources = new int[feeds.size()];
        int[] firstDays = new int[feeds.size()];
        int[] lastDays = new int[feeds.size()];
        int[] operatorStart = new int[feeds.size() + 1];
        IntList operators = new IntList();
        IntList operatorLastDays = new IntList();
        for (int feed = 0; feed < feeds.size(); feed++) {
            FeedTimespan timespan = feeds.get(feed);
            sources[feed] = strings.index(timespan.source());
            firstDays[feed] = Math.toIntExact(timespan.validity().firstDay().toEpochDay());
            lastDays[feed] = Math.toIntExact(timespan.validity().lastDay().toEpochDay());
            for (OperatorEnd end : timespan.earlyEndingOperators()) {
                operators.add(strings.index(end.getOperator()));
                operatorLastDays.add(Math.toIntExact(end.getLastServiceDate().toEpochDay()));
            }
            operatorStart[feed + 1] = operators.size();
        }
        return new int[][] {sources, firstDays, lastDays, operatorStart, operators.toArray(),
                operatorLastDays.toArray()};
    }

    private static List<FeedTimespan> readFeeds(BinaryBlocks.Reader in, String[] strings) throws IOException {
        int[] sources = in.ints();
        int[] firstDays = in.ints();
        int[] lastDays = in.ints();
        int[] operatorStart = in.ints();
        int[] operators = in.ints();
        int[] operatorLastDays = in.ints();
        List<FeedTimespan> feeds = new ArrayList<>(sources.length);
        for (int feed = 0; feed < sources.length; feed++) {
            List<OperatorEnd> earlyEnding = new ArrayList<>();
            for (int i = operatorStart[feed]; i < operatorStart[feed + 1]; i++) {
                earlyEnding.add(new OperatorEnd(strings[operators[i]], LocalDate.ofEpochDay(operatorLastDays[i])));
            }
            FeedValidity validity = new FeedValidity(LocalDate.ofEpochDay(firstDays[feed]),
                    LocalDate.ofEpochDay(lastDays[feed]));
            feeds.add(new FeedTimespan(strings[sources[feed]], validity, List.copyOf(earlyEnding)));
        }
        return feeds;
    }

    private static String string(String[] strings, int index) {
        return index == NO_STRING ? null : strings[index];
    }

    private static long coordinateBits(Double degrees) {
        return Double.doubleToLongBits(degrees != null ? degrees : Double.NaN);
    }

    private static Double coordinate(long bits) {
        double degrees = Double.longBitsToDouble(bits);
        return Double.isNaN(degrees) ? null : degrees;
    }

    private static byte[] hash(List<Path> inputFiles) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
        for (Path inputFile : inputFiles) {
            try (InputStream in = new DigestInputStream(Files.newInputStream(inputFile), digest)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
        }
        return digest.digest();
    }
}
