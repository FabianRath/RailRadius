package com.railreach.service;

import com.railreach.model.Connection;
import com.railreach.model.JourneyPath;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.BiConsumer;

/**
 * A compact search's result (S19): the labels of its chosen paths and of the rides before them, copied out of the
 * search's arena, and per station its chosen labels by ascending transfers. A journey's stations, connections and
 * running days are built from the {@link TimetableStore} when it is asked for, so a stored result is a few arrays.
 * A result with fewer transfers is a view on the same arrays.
 */
final class CompactResult implements SearchResult {

    private static final List<PathSummary> START_PATHS = List.of(new PathSummary(0, 0));

    /** The columns of {@link LabelArena} that journeys need, as arrays of exact size. */
    record Labels(int[] trip, int[] previous, int[] durationMinutes, short[] boardIdx, short[] alightIdx,
                  byte[] transfers) {

        long sizeBytes() {
            return (long) trip.length * (3 * Integer.BYTES + 2 * Short.BYTES + Byte.BYTES);
        }
    }

    private final TimetableStore store;
    private final TransitGraph graph;
    private final String startId;
    private final Labels labels;
    private final ChosenPaths.ByStation chosen;
    private final int maxTransfers;

    CompactResult(TimetableStore store, TransitGraph graph, String startId, Labels labels,
                  ChosenPaths.ByStation chosen, int maxTransfers) {
        this.store = store;
        this.graph = graph;
        this.startId = startId;
        this.labels = labels;
        this.chosen = chosen;
        this.maxTransfers = maxTransfers;
    }

    @Override
    public void forEachStation(BiConsumer<String, List<PathSummary>> action) {
        action.accept(startId, START_PATHS);
        for (int station = 0; station + 1 < chosen.start().length; station++) {
            if (hasPathWithin(station)) action.accept(store.stations().id(station), paths(station));
        }
    }

    @Override
    public List<PathSummary> paths(String stationId) {
        if (stationId.equals(startId)) return START_PATHS;
        OptionalInt station = store.stations().indexOf(stationId);
        return station.isPresent() ? paths(station.getAsInt()) : List.of();
    }

    @Override
    public Journey journey(String stationId, int transfers) {
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

    @Override
    public SearchResult withMaxTransfers(int transfers) {
        return new CompactResult(store, graph, startId, labels, chosen, Math.min(transfers, maxTransfers));
    }

    @Override
    public int stationCount() {
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
