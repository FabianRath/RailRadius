package com.railradius.service;

import com.railradius.model.Connection;
import com.railradius.model.JourneyPath;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.BiConsumer;

/**
 * What a search found (S19): per station its paths by ascending transfers ({@link CompactReachabilitySearch#search}).
 * It holds the labels of the chosen paths and of the rides before them, copied out of the search's arena, and per
 * station its chosen labels by ascending transfers. A path's transfers and duration are at hand; its stations,
 * connections and running days are built from the {@link TimetableStore} when it is asked for ({@link #journey}), so
 * a stored result is a few arrays. A result with fewer transfers is a view on the same arrays. It keeps the
 * {@link ServedGraph} it was searched on, so it is described by that graph even after a load replaced it (#19).
 */
final class CompactResult {

    private static final List<PathSummary> START_PATHS = List.of(new PathSummary(0, 0));

    /** A path's transfers and duration, which is all the stream sends of it. */
    record PathSummary(int transfers, int durationMinutes) {}

    /** A path's stations and connections, and the exact days on which the whole journey runs. */
    record Journey(JourneyPath path, ServiceDays runningDays) {}

    /** The columns of {@link LabelArena} that journeys need, as arrays of exact size. */
    record Labels(int[] trip, int[] previous, int[] durationMinutes, short[] boardIdx, short[] alightIdx,
                  byte[] transfers) {

        long sizeBytes() {
            return (long) trip.length * (3 * Integer.BYTES + 2 * Short.BYTES + Byte.BYTES);
        }
    }

    private final ServedGraph served;
    private final TimetableStore store;
    // Only for its clock: which days are past
    private final TransitGraph graph;
    private final String startId;
    private final Labels labels;
    private final ChosenPaths.ByStation chosen;
    private final int maxTransfers;

    CompactResult(ServedGraph served, TransitGraph graph, String startId, Labels labels,
                  ChosenPaths.ByStation chosen, int maxTransfers) {
        this.served = served;
        this.store = served.store();
        this.graph = graph;
        this.startId = startId;
        this.labels = labels;
        this.chosen = chosen;
        this.maxTransfers = maxTransfers;
    }

    /** Calls the action with every station that has a path and its paths; the start station has its one empty path. */
    void forEachStation(BiConsumer<String, List<PathSummary>> action) {
        action.accept(startId, START_PATHS);
        for (int station = 0; station + 1 < chosen.start().length; station++) {
            if (hasPathWithin(station)) action.accept(store.stations().id(station), paths(station));
        }
    }

    /** The station's paths by ascending transfers; empty if it has none. */
    List<PathSummary> paths(String stationId) {
        if (stationId.equals(startId)) return START_PATHS;
        OptionalInt station = store.stations().indexOf(stationId);
        return station.isPresent() ? paths(station.getAsInt()) : List.of();
    }

    /**
     * The station's path with this many transfers, one of {@link #paths}.
     *
     * @throws IllegalArgumentException if the station has no such path
     */
    Journey journey(String stationId, int transfers) {
        if (stationId.equals(startId) && transfers == 0) return journey(LabelArena.NONE);
        OptionalInt station = store.stations().indexOf(stationId);
        if (station.isPresent()) {
            for (int i = chosen.start()[station.getAsInt()]; i < chosen.start()[station.getAsInt() + 1]; i++) {
                int label = chosen.labels()[i];
                if (labels.transfers()[label] == transfers && transfers <= maxTransfers) return journey(label);
            }
        }
        throw new IllegalArgumentException("No path with " + transfers + " transfers to " + stationId);
    }

    /**
     * The paths with at most this many transfers. Rounds of the search never depend on later rounds, so that is
     * exactly the result of a search with that many.
     */
    CompactResult withMaxTransfers(int transfers) {
        return new CompactResult(served, graph, startId, labels, chosen, Math.min(transfers, maxTransfers));
    }

    /** The graph the result was searched on. */
    ServedGraph served() {
        return served;
    }

    /** Stations with a path, the start station included. */
    int stationCount() {
        int count = 1;
        for (int station = 0; station + 1 < chosen.start().length; station++) {
            if (hasPathWithin(station)) count++;
        }
        return count;
    }

    /** Bytes of the arrays; a view shares them with the result it was cut from. */
    long sizeBytes() {
        return labels.sizeBytes() + chosen.sizeBytes();
    }

    private List<PathSummary> paths(int station) {
        List<PathSummary> paths = new ArrayList<>();
        for (int i = chosen.start()[station]; i < chosen.start()[station + 1]; i++) {
            int label = chosen.labels()[i];
            if (labels.transfers()[label] > maxTransfers) break;
            paths.add(new PathSummary(labels.transfers()[label], labels.durationMinutes()[label]));
        }
        return paths;
    }

    // A station's first path has its fewest transfers
    private boolean hasPathWithin(int station) {
        int first = chosen.start()[station];
        return first < chosen.start()[station + 1] && labels.transfers()[chosen.labels()[first]] <= maxTransfers;
    }

    /** The journey ending with the ride of label, or the start's empty one for {@link LabelArena#NONE}. */
    private Journey journey(int last) {
        List<Integer> rides = new ArrayList<>();
        for (int label = last; label != LabelArena.NONE; label = labels.previous()[label]) {
            rides.add(label);
        }
        List<String> stationIds = new ArrayList<>(List.of(startId));
        List<Connection> connections = new ArrayList<>();
        JourneyRunningDays.SoFar days = JourneyRunningDays.SoFar.START;
        for (int label : rides.reversed()) {
            int firstEvent = store.firstEvent(labels.trip()[label]);
            int boardEvent = firstEvent + labels.boardIdx()[label];
            int alightEvent = firstEvent + labels.alightIdx()[label];
            for (int event = boardEvent; event < alightEvent; event++) {
                connections.add(store.legConnection(event));
                stationIds.add(store.stations().id(store.station(event + 1)));
            }
            days = JourneyRunningDays.withRide(days, store, boardEvent, alightEvent, graph);
        }
        boolean start = last == LabelArena.NONE;
        JourneyPath path = JourneyPath.builder()
                .stationIds(stationIds)
                .connections(connections)
                .transfers(start ? 0 : (int) labels.transfers()[last])
                .totalDurationMinutes(start ? 0 : labels.durationMinutes()[last])
                .build();
        return new Journey(path, days.days());
    }
}
