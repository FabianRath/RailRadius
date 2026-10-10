package com.railreach.service;

import com.railreach.model.Connection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Lays out {@link MergedTrips} as the arrays of a {@link TimetableStore}. */
final class TimetableStoreBuilder {

    private static final int SECONDS_SHIFT = Integer.SIZE;
    private static final long EVENT_MASK = 0xFFFF_FFFFL;

    /** Everything a TimetableStore holds except what it derives itself. */
    record Parts(StationTable stations, int[] tripFirstEvent, int[] evStation, int[] evDepSec, int[] evArrSec,
                 DayPatterns dayPatterns, int[] tripDayPattern, PackedStrings routeNames, int[] tripRouteName,
                 PackedStrings memberTripIds, int[] tripMemberStart, int[] depStart, int[] depSec, int[] depEvent) {}

    private record Events(int[] tripFirstEvent, int[] station, int[] depSec, int[] arrSec) {}

    private record Departures(int[] start, int[] seconds, int[] events) {}

    private TimetableStoreBuilder() {
    }

    static Parts parts(MergedTrips merged) {
        List<MergedTrips.Trip> trips = merged.trips();
        Events events = events(trips);
        DayPatterns.Builder dayPatterns = new DayPatterns.Builder();
        int[] tripDayPattern = trips.stream().mapToInt(trip -> dayPatterns.intern(trip.days())).toArray();
        Map<String, Integer> routeNameIndexes = new HashMap<>();
        int[] tripRouteName = trips.stream().mapToInt(trip -> routeNameIndex(trip, routeNameIndexes)).toArray();
        List<String> memberTripIds = new ArrayList<>();
        int[] tripMemberStart = new int[trips.size() + 1];
        for (int trip = 0; trip < trips.size(); trip++) {
            memberTripIds.addAll(trips.get(trip).memberTripIds());
            tripMemberStart[trip + 1] = memberTripIds.size();
        }
        Departures departures = departures(merged.stationIndexes().size(), events);
        StationTable stations = StationTable.of(inIndexOrder(merged.stationIndexes()));
        return new Parts(stations, events.tripFirstEvent(), events.station(), events.depSec(), events.arrSec(),
                dayPatterns.build(), tripDayPattern,
                PackedStrings.of(inIndexOrder(routeNameIndexes)), tripRouteName, PackedStrings.of(memberTripIds),
                tripMemberStart, departures.start(), departures.seconds(), departures.events());
    }

    private static Events events(List<MergedTrips.Trip> trips) {
        int[] tripFirstEvent = new int[trips.size() + 1];
        for (int trip = 0; trip < trips.size(); trip++) {
            tripFirstEvent[trip + 1] = tripFirstEvent[trip] + trips.get(trip).stops().size();
        }
        int eventCount = tripFirstEvent[trips.size()];
        int[] station = new int[eventCount];
        int[] depSec = new int[eventCount];
        int[] arrSec = new int[eventCount];
        Arrays.fill(depSec, TimetableStore.NO_LEG);
        Arrays.fill(arrSec, TimetableStore.NO_LEG);
        for (int trip = 0; trip < trips.size(); trip++) {
            MergedTrips.Trip merged = trips.get(trip);
            int first = tripFirstEvent[trip];
            System.arraycopy(merged.stationIndexes(), 0, station, first, merged.stationIndexes().length);
            Connection[] legs = merged.legConnections();
            for (int leg = 0; leg < legs.length; leg++) {
                if (legs[leg] == null) continue;
                depSec[first + leg] = legs[leg].getDepartureTimeSeconds();
                arrSec[first + leg + 1] = legs[leg].getArrivalTimeSeconds();
            }
        }
        return new Events(tripFirstEvent, station, depSec, arrSec);
    }

    // Every connection of a trip carries its route's name; a trip without connections has none
    private static int routeNameIndex(MergedTrips.Trip trip, Map<String, Integer> routeNameIndexes) {
        return Arrays.stream(trip.legConnections()).filter(Objects::nonNull).map(Connection::getRouteName)
                .filter(Objects::nonNull).findFirst()
                .map(name -> routeNameIndexes.computeIfAbsent(name, k -> routeNameIndexes.size()))
                .orElse(TimetableStore.NO_ROUTE_NAME);
    }

    /** Each station's events with a leg, by departure time, equal times in event order. */
    private static Departures departures(int stationCount, Events events) {
        int[] start = new int[stationCount + 1];
        for (int event = 0; event < events.station().length; event++) {
            if (events.depSec()[event] != TimetableStore.NO_LEG) start[events.station()[event] + 1]++;
        }
        Arrays.parallelPrefix(start, Integer::sum);
        // Seconds in the high half, the event in the low half: sorting the keys sorts by time, then by event
        long[] keys = new long[start[stationCount]];
        int[] next = start.clone();
        for (int event = 0; event < events.station().length; event++) {
            int seconds = events.depSec()[event];
            if (seconds == TimetableStore.NO_LEG) continue;
            keys[next[events.station()[event]]++] = (long) seconds << SECONDS_SHIFT | event;
        }
        int[] seconds = new int[keys.length];
        int[] departureEvents = new int[keys.length];
        for (int station = 0; station < stationCount; station++) {
            Arrays.sort(keys, start[station], start[station + 1]);
        }
        for (int position = 0; position < keys.length; position++) {
            seconds[position] = (int) (keys[position] >>> SECONDS_SHIFT);
            departureEvents[position] = (int) (keys[position] & EVENT_MASK);
        }
        return new Departures(start, seconds, departureEvents);
    }

    private static List<String> inIndexOrder(Map<String, Integer> indexes) {
        String[] byIndex = new String[indexes.size()];
        indexes.forEach((name, index) -> byIndex[index] = name);
        return List.of(byIndex);
    }
}
