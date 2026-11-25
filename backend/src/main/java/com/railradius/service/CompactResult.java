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

    /** A ride as positions in the store: its trip and the events it is boarded and left at. */
    record Ride(int trip, int boardEvent, int alightEvent) {}

    /**
     * A path's rides in order and the exact days on which the whole journey runs: a journey without trip IDs, which
     * a browser's store lacks (#10), and what a browser sends to have the server build the journey (#11).
     */
    record RideList(List<Ride> rides, ServiceDays runningDays) {}

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
        return journey(label(stationId, transfers));
    }

    /**
     * The rides of the station's path with this many transfers, one of {@link #paths}.
     *
     * @throws IllegalArgumentException if the station has no such path
     */
    RideList rideList(String stationId, int transfers) {
        return rideList(label(stationId, transfers));
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

    /** The label of the station's path's last ride, or {@link LabelArena#NONE} for the start's empty path. */
    private int label(String stationId, int transfers) {
        if (stationId.equals(startId) && transfers == 0) return LabelArena.NONE;
        OptionalInt station = store.stations().indexOf(stationId);
        if (station.isPresent()) {
            for (int i = chosen.start()[station.getAsInt()]; i < chosen.start()[station.getAsInt() + 1]; i++) {
                int label = chosen.labels()[i];
                if (labels.transfers()[label] == transfers && transfers <= maxTransfers) return label;
            }
        }
        throw new IllegalArgumentException("No path with " + transfers + " transfers to " + stationId);
    }

    /** The rides up to and including the one of label; none for {@link LabelArena#NONE}. */
    private RideList rideList(int last) {
        List<Integer> backwards = new ArrayList<>();
        for (int label = last; label != LabelArena.NONE; label = labels.previous()[label]) {
            backwards.add(label);
        }
        List<Ride> rides = new ArrayList<>();
        for (int label : backwards.reversed()) {
            int firstEvent = store.firstEvent(labels.trip()[label]);
            rides.add(new Ride(labels.trip()[label], firstEvent + labels.boardIdx()[label],
                    firstEvent + labels.alightIdx()[label]));
        }
        return rideList(store, rides, graph);
    }

    /** The journey ending with the ride of label, or the start's empty one for {@link LabelArena#NONE}. */
    private Journey journey(int last) {
        boolean start = last == LabelArena.NONE;
        return journey(store, startId, rideList(last), start ? 0 : labels.durationMinutes()[last]);
    }

    /** The rides with the exact days on which all of them run; graph only tells which days are past. */
    static RideList rideList(TimetableStore store, List<Ride> rides, TransitGraph graph) {
        JourneyRunningDays.SoFar days = JourneyRunningDays.SoFar.START;
        for (Ride ride : rides) {
            days = JourneyRunningDays.withRide(days, store, ride.boardEvent(), ride.alightEvent(), graph);
        }
        return new RideList(rides, days.days());
    }

    /** The journey of the rides from startId, which the first ride boards at; one transfer between two rides. */
    static Journey journey(TimetableStore store, String startId, RideList rideList, int durationMinutes) {
        List<Connection> connections = new ArrayList<>();
        for (Ride ride : rideList.rides()) {
            for (int event = ride.boardEvent(); event < ride.alightEvent(); event++) {
                connections.add(store.legConnection(event));
            }
        }
        JourneyPath path = JourneyPath.builder()
                .stationIds(stationIds(store, startId, rideList.rides()))
                .connections(connections)
                .transfers(Math.max(0, rideList.rides().size() - 1))
                .totalDurationMinutes(durationMinutes)
                .build();
        return new Journey(path, rideList.runningDays());
    }

    /** The stations the rides pass from startId on, each stop once: the journey's path. */
    static List<String> stationIds(TimetableStore store, String startId, List<Ride> rides) {
        List<String> stationIds = new ArrayList<>(List.of(startId));
        for (Ride ride : rides) {
            for (int event = ride.boardEvent(); event < ride.alightEvent(); event++) {
                stationIds.add(store.stations().id(store.station(event + 1)));
            }
        }
        return stationIds;
    }
}
