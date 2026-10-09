package com.railreach.service;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * The search's static data in flat arrays (S17), built from {@link MergedTrips} like {@link SearchTimetables} and
 * numbered the same way, so the two can be compared. It does without an object per trip, stop or departure, and
 * without strings except in packed tables.
 * <p>
 * A trip's stops are events {@code tripFirstEvent[trip]} up to {@code tripFirstEvent[trip + 1]}, numbered across all
 * trips. Event e leaves its station at {@code evDepSec[e]} towards event e + 1, or has no leg there
 * ({@link CachedTrip#NO_LEG}, also on a trip's last stop); it arrives at {@code evArrSec[e]}, {@link CachedTrip#NO_LEG}
 * on the first stop and after a missing leg. A station's departures are positions {@code depStart[station]} up to
 * {@code depStart[station + 1]} of {@code depSec}/{@code depEvent}, by departure time, equal times in event order
 * (trip order, then stop order): the order of {@link SearchTimetables#departuresAt}.
 */
@Slf4j
final class TimetableStore {

    private static final int BITS_PER_WORD = Long.SIZE;
    static final int NO_ROUTE_NAME = -1;

    private final StationTable stations;
    private final int[] tripFirstEvent;
    // Derived from tripFirstEvent: the search looks up the trip of every departure it scans
    private final int[] evTrip;
    private final int[] evStation;
    private final int[] evDepSec;
    private final int[] evArrSec;
    private final DayPatterns dayPatterns;
    private final int[] tripDayPattern;
    private final PackedStrings routeNames;
    private final int[] tripRouteName;
    private final PackedStrings memberTripIds;
    private final int[] tripMemberStart;
    private final int[] depStart;
    private final int[] depSec;
    private final int[] depEvent;
    private final long[] regularDeparture;

    private TimetableStore(TimetableStoreBuilder.Parts parts) {
        this.stations = parts.stations();
        this.tripFirstEvent = parts.tripFirstEvent();
        this.evTrip = eventTrips(parts.tripFirstEvent());
        this.evStation = parts.evStation();
        this.evDepSec = parts.evDepSec();
        this.evArrSec = parts.evArrSec();
        this.dayPatterns = parts.dayPatterns();
        this.tripDayPattern = parts.tripDayPattern();
        this.routeNames = parts.routeNames();
        this.tripRouteName = parts.tripRouteName();
        this.memberTripIds = parts.memberTripIds();
        this.tripMemberStart = parts.tripMemberStart();
        this.depStart = parts.depStart();
        this.depSec = parts.depSec();
        this.depEvent = parts.depEvent();
        this.regularDeparture = regularDepartures();
    }

    static TimetableStore build(TransitGraph graph) {
        long startMs = System.currentTimeMillis();
        TimetableStore store = new TimetableStore(TimetableStoreBuilder.parts(MergedTrips.of(graph)));
        log.info("Timetable store built in {} ms: {} trips, {} stop events, {} stations, {} day patterns, {}",
                System.currentTimeMillis() - startMs, store.tripCount(), store.eventCount(), store.stations.size(),
                store.dayPatterns.size(), String.format("%.1f MB", store.sizeBytes() / 1e6));
        return store;
    }

    StationTable stations() {
        return stations;
    }

    DayPatterns dayPatterns() {
        return dayPatterns;
    }

    int tripCount() {
        return tripFirstEvent.length - 1;
    }

    int eventCount() {
        return evStation.length;
    }

    int firstEvent(int trip) {
        return tripFirstEvent[trip];
    }

    /** One past the trip's last event. */
    int endEvent(int trip) {
        return tripFirstEvent[trip + 1];
    }

    int tripOf(int event) {
        return evTrip[event];
    }

    int station(int event) {
        return evStation[event];
    }

    int departureSeconds(int event) {
        return evDepSec[event];
    }

    int arrivalSeconds(int event) {
        return evArrSec[event];
    }

    /** Whether the trip runs on from this event to the next. */
    boolean hasLeg(int event) {
        return evDepSec[event] != CachedTrip.NO_LEG;
    }

    /** Whether the trip may be ridden away from this event ({@link RareTrainFilter#regularDepartures}). */
    boolean isRegularDeparture(int event) {
        return (regularDeparture[event / BITS_PER_WORD] & 1L << event) != 0;
    }

    int dayPattern(int trip) {
        return tripDayPattern[trip];
    }

    /** The kept trip's ID: the first of {@link #memberTripIds}. */
    String tripId(int trip) {
        return memberTripIds.get(tripMemberStart[trip]);
    }

    /** The trips merged into this one, in trip ID order. */
    List<String> memberTripIds(int trip) {
        return IntStream.range(tripMemberStart[trip], tripMemberStart[trip + 1]).mapToObj(memberTripIds::get).toList();
    }

    Optional<String> routeName(int trip) {
        int name = tripRouteName[trip];
        return name == NO_ROUTE_NAME ? Optional.empty() : Optional.of(routeNames.get(name));
    }

    /** First position of the station's departures. */
    int departuresStart(int station) {
        return depStart[station];
    }

    /** One past the last position of the station's departures. */
    int departuresEnd(int station) {
        return depStart[station + 1];
    }

    int departureSecondsAt(int position) {
        return depSec[position];
    }

    int departureEventAt(int position) {
        return depEvent[position];
    }

    /** The position of the station's first departure at or after departureSeconds. */
    int firstDepartureAt(int station, int departureSeconds) {
        int low = depStart[station];
        int high = depStart[station + 1];
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (depSec[mid] < departureSeconds) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    /**
     * The days of a ride boarded at boardEvent and left at alightEvent of one trip: its trip's and those of every trip
     * that leaves the board station at the same time and, at its first later stop there, reaches the alight station
     * at the same time. Feeds publish one train as several trips with different days (NS: train 9535 as 7 trips, one
     * stop ID apart), so they count as one for the journey's days.
     */
    ServiceDays rideDays(int boardEvent, int alightEvent) {
        int boardStation = evStation[boardEvent];
        int departure = evDepSec[boardEvent];
        ServiceDays days = dayPatterns.days(tripDayPattern[tripOf(boardEvent)]);
        for (int position = firstDepartureAt(boardStation, departure);
             position < depStart[boardStation + 1] && depSec[position] == departure; position++) {
            int twin = depEvent[position];
            if (arrivesLike(twin, evStation[alightEvent], evArrSec[alightEvent])) {
                days = days.union(dayPatterns.days(tripDayPattern[tripOf(twin)]));
            }
        }
        return days;
    }

    /** Estimated bytes of the store's arrays and strings. */
    long sizeBytes() {
        long intArrays = (long) tripFirstEvent.length + evTrip.length + evStation.length + evDepSec.length
                + evArrSec.length + tripDayPattern.length + tripRouteName.length + tripMemberStart.length
                + depStart.length + depSec.length + depEvent.length;
        return intArrays * Integer.BYTES + (long) regularDeparture.length * Long.BYTES + stations.sizeBytes()
                + dayPatterns.sizeBytes() + routeNames.sizeBytes() + memberTripIds.sizeBytes();
    }

    private static int[] eventTrips(int[] tripFirstEvent) {
        int[] trips = new int[tripFirstEvent[tripFirstEvent.length - 1]];
        for (int trip = 0; trip + 1 < tripFirstEvent.length; trip++) {
            Arrays.fill(trips, tripFirstEvent[trip], tripFirstEvent[trip + 1], trip);
        }
        return trips;
    }

    private boolean arrivesLike(int departureEvent, int station, int arrivalSeconds) {
        int end = endEvent(tripOf(departureEvent));
        for (int event = departureEvent + 1; event < end && hasLeg(event - 1); event++) {
            if (evStation[event] == station) return evArrSec[event] == arrivalSeconds;
        }
        return false;
    }

    /**
     * Per event: whether all trips leaving its station at its time together run on enough days
     * ({@link RareTrainFilter#runsOftenEnough}). Equal times are next to each other in a station's departures, so
     * each such group is checked once.
     */
    private long[] regularDepartures() {
        long[] regular = new long[(eventCount() + BITS_PER_WORD - 1) / BITS_PER_WORD];
        for (int station = 0; station < stations.size(); station++) {
            int groupStart = depStart[station];
            while (groupStart < depStart[station + 1]) {
                int groupEnd = groupStart;
                ServiceDays days = null;
                for (; groupEnd < depStart[station + 1] && depSec[groupEnd] == depSec[groupStart]; groupEnd++) {
                    ServiceDays twinDays = dayPatterns.days(tripDayPattern[tripOf(depEvent[groupEnd])]);
                    days = days == null ? twinDays : days.union(twinDays);
                }
                if (RareTrainFilter.runsOftenEnough(days)) {
                    for (int position = groupStart; position < groupEnd; position++) {
                        regular[depEvent[position] / BITS_PER_WORD] |= 1L << depEvent[position];
                    }
                }
                groupStart = groupEnd;
            }
        }
        return regular;
    }
}
