package com.railradius.service;

import com.railradius.dto.ReachableStation;
import com.railradius.dto.RideListJourneyRequest;
import com.railradius.dto.RidePosition;
import com.railradius.util.GtfsTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Builds a station's journey from the rides a browser's search found (#11), without searching. The browser names its
 * rides as positions in the store of the browser data it searched on; they are checked to be a journey within the
 * wait at changes on the store the server serves now, and built like a stored result's ({@link CompactResult#journey}).
 */
@Component
@RequiredArgsConstructor
public class RideListJourneys {

    private final BrowserDataStore browserDataStore;
    private final SearchRateLimiter rateLimiter;
    private final JourneyLegBuilder legBuilder;
    private final TrackLines trackLines;
    // Only for its clock: which days are past
    private final TransitGraph graph;

    /**
     * The journey of the rides, to the station the last one alights at. Checked before the client is charged.
     *
     * @throws SearchRefusal if the version is not the served data's, the rides are no journey within the wait at
     *                       changes, or the client's budget is too low
     */
    public Optional<ReachableStation> journey(SearchAdmission.Client client, RideListJourneyRequest request) {
        List<RidePosition> positions = request.getRides();
        if (positions == null || positions.isEmpty() || positions.size() > SearchOptions.MAX_TRANSFERS + 1) {
            throw SearchRefusal.invalid("A journey has 1 to " + (SearchOptions.MAX_TRANSFERS + 1) + " rides");
        }
        ServedGraph served = browserDataStore.servedWithVersion(request.getVersion())
                .orElseThrow(() -> SearchRefusal.invalid("The timetable has changed, please search again"));
        TimetableStore store = served.store();
        List<CompactResult.Ride> rides = positions.stream().map(position -> ride(store, position)).toList();
        String startId = store.stations().id(store.station(rides.getFirst().boardEvent()));
        SearchOptions options = SearchOptions.of(startId, rides.size() - 1, request.getMinTransferMinutes(),
                request.getMaxTransferMinutes());
        int durationMinutes = durationMinutes(store, rides, options);
        if (!client.admin()) rateLimiter.chargeRideListJourney(client.ip());

        CompactResult.RideList rideList = CompactResult.rideList(store, rides, graph);
        String targetId = store.stations().id(store.station(rides.getLast().alightEvent()));
        return legBuilder.toReachableStation(served, targetId,
                CompactResult.journey(store, startId, rideList, durationMinutes))
                .map(station -> trackLines.addTo(station, served::station));
    }

    /** The ride, if its trip runs from its board event to its later alight event. */
    private static CompactResult.Ride ride(TimetableStore store, RidePosition position) {
        int trip = position.getTrip();
        if (trip < 0 || trip >= store.tripCount()) throw SearchRefusal.invalid("A ride's trip does not exist");
        int boardEvent = position.getBoardEvent();
        int alightEvent = position.getAlightEvent();
        if (boardEvent < store.firstEvent(trip) || alightEvent >= store.endEvent(trip)) {
            throw SearchRefusal.invalid("A ride's stops are not on its trip");
        }
        if (alightEvent <= boardEvent) throw SearchRefusal.invalid("A ride must alight after it boards");
        for (int event = boardEvent; event < alightEvent; event++) {
            if (!store.hasLeg(event)) throw SearchRefusal.invalid("A ride's trip does not run between its stops");
        }
        return new CompactResult.Ride(trip, boardEvent, alightEvent);
    }

    /**
     * The journey's minutes as the search counts them: each ride's legs and the dwells between them, and each wait
     * at a change.
     *
     * @throws SearchRefusal if a ride does not board where the one before alights, within the wait at changes
     */
    private static int durationMinutes(TimetableStore store, List<CompactResult.Ride> rides, SearchOptions options) {
        int minutes = 0;
        CompactResult.Ride previous = null;
        for (CompactResult.Ride ride : rides) {
            if (previous != null) {
                if (store.station(previous.alightEvent()) != store.station(ride.boardEvent())) {
                    throw SearchRefusal.invalid("Each ride must board where the one before it alights");
                }
                int arrivalSeconds = store.arrivalSeconds(previous.alightEvent());
                int departureSeconds = store.departureSeconds(ride.boardEvent());
                if (!catches(arrivalSeconds, departureSeconds, options)) {
                    throw SearchRefusal.invalid("A wait at a change is outside the allowed wait");
                }
                minutes += GtfsTime.diffMinutes(arrivalSeconds, departureSeconds);
            }
            for (int legEvent = ride.boardEvent(); legEvent < ride.alightEvent(); legEvent++) {
                minutes += store.rideLegMinutes(ride.boardEvent(), legEvent);
            }
            previous = ride;
        }
        return minutes;
    }

    /** Whether the search boards the departure from the arrival: within the wait the same day or past midnight. */
    private static boolean catches(int arrivalSeconds, int departureSeconds, SearchOptions options) {
        int minWaitSeconds = options.minTransferMins() * GtfsTime.SECONDS_PER_MINUTE;
        int maxWaitSeconds = options.maxTransferMins() * GtfsTime.SECONDS_PER_MINUTE;
        int sameDayWaitSeconds = departureSeconds - arrivalSeconds;
        int overnightWaitSeconds = sameDayWaitSeconds + GtfsTime.SECONDS_PER_DAY;
        return sameDayWaitSeconds >= minWaitSeconds && sameDayWaitSeconds <= maxWaitSeconds
                || overnightWaitSeconds >= minWaitSeconds
                && overnightWaitSeconds <= CompactTransferRound.overnightMaxWaitSeconds(maxWaitSeconds);
    }
}
