package com.railradius.service;

import com.railradius.model.Station;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The timetable a browser searches on (#9): a {@link BinaryBlocks} file with only what the search and the map need.
 * <p>
 * Blocks: the notice (UTF-8 text: ODbL and the feeds it comes from); the station IDs, names and countries
 * ({@link PackedStrings}, a missing name or country as ""); latitudes and longitudes as float bits (NaN if missing);
 * the distinct stop sequences, as each one's stop count and their stations, and each trip's sequence (#48: many
 * trips stop at the same stations, so each sequence is stored once); the trips' times ({@link BrowserTripTimes});
 * the day patterns; each trip's day pattern; the regular departures ({@link TimetableStore#regularDepartureWords}).
 * <p>
 * The regular departures are shipped, not derived again, because they count days the file may leave out (#47): the
 * server works them out from every day of the feeds. They are almost all ones, so they gzip to very little.
 * <p>
 * Left out, and why: trip IDs, route names, train numbers and translated names belong to journeys, which the server
 * builds (#11); the station name index stays on the server; the departures index is derived again on reading, as the
 * store does. Times as differences within a trip are small, repeating numbers, which cut the gzipped file of all of
 * Europe to a fifth (docs/HOSTING.md).
 */
final class BrowserDataFile {

    /** Bump on every change of the layout: a browser with another version searches on the server. */
    static final int FORMAT_VERSION = 4;

    private static final String ODBL_URL = "https://opendatacommons.org/licenses/odbl/1-0/";
    // The file has no other input to hash: its own content hash is its version (BrowserDataStore)
    private static final byte[] NO_HASH = new byte[BinaryBlocks.HASH_BYTES];

    private BrowserDataFile() {
    }

    /** What a browser reads: the stations by store index, the store, and the notice. */
    record BrowserData(List<Station> stations, TimetableStore store, String notice) {}

    /** Writes the served graph's file. Every station of the store must be one of the graph's stations. */
    static void write(ServedGraph served, Path file) throws IOException {
        TimetableStore store = served.store();
        try (BinaryBlocks.Writer out = new BinaryBlocks.Writer(file, FORMAT_VERSION, NO_HASH)) {
            out.bytes(notice(served.feeds()).getBytes(StandardCharsets.UTF_8));
            writeStations(served, out);
            writeTrips(store, out);
            store.dayPatterns().writeTo(out);
            out.ints(tripDayPatterns(store));
            out.longs(store.regularDepartureWords());
        }
    }

    static BrowserData read(Path file) throws IOException {
        try (BinaryBlocks.Reader in = new BinaryBlocks.Reader(file, FORMAT_VERSION)) {
            return read(in);
        }
    }

    /** Reads every block of the reader; the browser engine's reader reads the file from memory. */
    static BrowserData read(BinaryBlocks.Reader in) throws IOException {
        String notice = new String(in.bytes(), StandardCharsets.UTF_8);
        PackedStrings ids = PackedStrings.readFrom(in);
        List<Station> stations = readStations(ids, in);
        int[] sequenceStopCounts = in.ints();
        int[] sequenceStations = in.ints();
        int[] tripSequence = in.ints();
        int[] tripFirstEvent = tripFirstEvents(sequenceStopCounts, tripSequence);
        int[] evStation = eventStations(sequenceStopCounts, sequenceStations, tripSequence, tripFirstEvent);
        BrowserTripTimes.Times times = BrowserTripTimes.read(in, tripFirstEvent);
        DayPatterns dayPatterns = DayPatterns.readFrom(in);
        int[] tripDayPattern = in.ints();
        long[] regularDeparture = in.longs();
        in.checkAllRead();
        StationTable stationTable = StationTable.of(stations.stream().map(Station::getId).toList());
        TimetableStore store = TimetableStore.of(TimetableStoreBuilder.withoutTripNames(stationTable,
                tripFirstEvent, evStation, times.evDepSec(), times.evArrSec(), dayPatterns, tripDayPattern), regularDeparture);
        return new BrowserData(stations, store, notice);
    }

    /** The ODbL notice and each feed with the days it covers. */
    static String notice(List<FeedTimespan> feeds) {
        StringBuilder text = new StringBuilder()
                .append("RailRadius rail timetable, derived from the GTFS feeds listed below.\n")
                .append("Made available under the Open Database License (ODbL) v1.0: ").append(ODBL_URL).append('\n')
                .append("Each source's publisher and licence: the Sources tab of the RailRadius About dialog.\n")
                .append("Feeds (source, first day, last day):\n");
        for (FeedTimespan feed : feeds) {
            text.append(feed.source()).append(' ').append(feed.validity().firstDay()).append(' ')
                    .append(feed.validity().lastDay()).append('\n');
        }
        return text.toString();
    }

    private static void writeStations(ServedGraph served, BinaryBlocks.Writer out) throws IOException {
        StationTable table = served.store().stations();
        List<String> ids = new ArrayList<>(table.size());
        List<String> names = new ArrayList<>(table.size());
        List<String> countries = new ArrayList<>(table.size());
        int[] latitudeBits = new int[table.size()];
        int[] longitudeBits = new int[table.size()];
        for (int index = 0; index < table.size(); index++) {
            Station station = Objects.requireNonNull(served.station(table.id(index)), table.id(index));
            ids.add(station.getId());
            names.add(Objects.requireNonNullElse(station.getName(), ""));
            countries.add(Objects.requireNonNullElse(station.getCountry(), ""));
            latitudeBits[index] = coordinateBits(station.getLatitude());
            longitudeBits[index] = coordinateBits(station.getLongitude());
        }
        PackedStrings.of(ids).writeTo(out);
        PackedStrings.of(names).writeTo(out);
        PackedStrings.of(countries).writeTo(out);
        out.ints(latitudeBits);
        out.ints(longitudeBits);
    }

    private static List<Station> readStations(PackedStrings ids, BinaryBlocks.Reader in) throws IOException {
        PackedStrings names = PackedStrings.readFrom(in);
        PackedStrings countries = PackedStrings.readFrom(in);
        int[] latitudeBits = in.ints();
        int[] longitudeBits = in.ints();
        List<Station> stations = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            stations.add(Station.builder()
                    .id(ids.get(index))
                    .name(emptyAsNull(names.get(index)))
                    .country(emptyAsNull(countries.get(index)))
                    .latitude(coordinate(latitudeBits[index]))
                    .longitude(coordinate(longitudeBits[index]))
                    .build());
        }
        return stations;
    }

    /** The stop sequences and each trip's, then the trips' times. */
    private static void writeTrips(TimetableStore store, BinaryBlocks.Writer out) throws IOException {
        writeSequences(store, out);
        BrowserTripTimes.write(store, out);
    }

    private static int[] tripDayPatterns(TimetableStore store) {
        int[] patterns = new int[store.tripCount()];
        for (int trip = 0; trip < patterns.length; trip++) {
            patterns[trip] = store.dayPattern(trip);
        }
        return patterns;
    }

    /** Each distinct sequence of stations once, numbered in the order trips first stop at it, and each trip's. */
    private static void writeSequences(TimetableStore store, BinaryBlocks.Writer out) throws IOException {
        Map<IntArrayKey, Integer> sequences = new HashMap<>();
        IntList stopCounts = new IntList();
        IntList stations = new IntList();
        int[] tripSequence = new int[store.tripCount()];
        for (int trip = 0; trip < store.tripCount(); trip++) {
            int[] tripStations = new int[store.endEvent(trip) - store.firstEvent(trip)];
            for (int stop = 0; stop < tripStations.length; stop++) {
                tripStations[stop] = store.station(store.firstEvent(trip) + stop);
            }
            tripSequence[trip] = sequences.computeIfAbsent(new IntArrayKey(tripStations), sequence -> {
                stopCounts.add(tripStations.length);
                for (int station : tripStations) stations.add(station);
                return stopCounts.size() - 1;
            });
        }
        out.ints(stopCounts.toArray());
        out.ints(stations.toArray());
        out.ints(tripSequence);
    }

    private static int[] tripFirstEvents(int[] sequenceStopCounts, int[] tripSequence) {
        int[] firstEvents = new int[tripSequence.length + 1];
        for (int trip = 0; trip < tripSequence.length; trip++) {
            firstEvents[trip + 1] = firstEvents[trip] + sequenceStopCounts[tripSequence[trip]];
        }
        return firstEvents;
    }

    /** Each trip's sequence's stations, as its events' stations. */
    private static int[] eventStations(int[] sequenceStopCounts, int[] sequenceStations, int[] tripSequence,
                                       int[] tripFirstEvent) {
        int[] sequenceStart = new int[sequenceStopCounts.length + 1];
        for (int sequence = 0; sequence < sequenceStopCounts.length; sequence++) {
            sequenceStart[sequence + 1] = sequenceStart[sequence] + sequenceStopCounts[sequence];
        }
        int[] evStation = new int[tripFirstEvent[tripSequence.length]];
        for (int trip = 0; trip < tripSequence.length; trip++) {
            System.arraycopy(sequenceStations, sequenceStart[tripSequence[trip]], evStation, tripFirstEvent[trip],
                    sequenceStopCounts[tripSequence[trip]]);
        }
        return evStation;
    }

    // Floats are exact to well under a metre at European latitudes, and halve the coordinates
    private static int coordinateBits(Double degrees) {
        return Float.floatToIntBits(degrees == null ? Float.NaN : degrees.floatValue());
    }

    private static Double coordinate(int bits) {
        float degrees = Float.intBitsToFloat(bits);
        return Float.isNaN(degrees) ? null : (double) degrees;
    }

    private static String emptyAsNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
