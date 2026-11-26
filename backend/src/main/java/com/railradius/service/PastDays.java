package com.railradius.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Drops the days before today from a built store (#45): the search never rides them, a journey's days start with its
 * first train's from today on (B31), and today only moves forward. Day patterns lose their past days and merge where
 * they become equal; trips left with no day go, with their events and departures, keeping their order. What was
 * worked out from every day stays as it was: the regular departures ({@link RareTrainFilter#runsOftenEnough} counts
 * past days, so a train must not turn rare by the pruning) and the {@link StationCounts}. The trains the load dropped
 * as rare were dropped before, counting past days too.
 */
final class PastDays {

    private static final int DROPPED = -1;
    private static final int BITS_PER_WORD = Long.SIZE;

    private PastDays() {
    }

    /** The store without the days before todayEpochDay and without the trips that run on none after. */
    static TimetableStore dropped(TimetableStore store, int todayEpochDay) {
        TimetableStoreBuilder.Parts parts = store.parts();
        DayPatterns.Builder upcoming = new DayPatterns.Builder();
        int[] newPattern = upcomingPatterns(parts.dayPatterns(), todayEpochDay, upcoming);
        int tripCount = store.tripCount();
        int[] newEvent = new int[store.eventCount()];
        Arrays.fill(newEvent, DROPPED);
        int[] tripFirstEvent = new int[tripCount + 1];
        IntList keptTrips = new IntList();
        int events = 0;
        for (int trip = 0; trip < tripCount; trip++) {
            if (newPattern[parts.tripDayPattern()[trip]] == DROPPED) continue;
            tripFirstEvent[keptTrips.size()] = events;
            keptTrips.add(trip);
            for (int event = store.firstEvent(trip); event < store.endEvent(trip); event++) {
                newEvent[event] = events++;
            }
        }
        tripFirstEvent[keptTrips.size()] = events;
        return cut(store, parts, Arrays.copyOf(tripFirstEvent, keptTrips.size() + 1), keptTrips, newEvent,
                newPattern, upcoming);
    }

    /** Per pattern its index among the upcoming ones, equal ones merged; {@link #DROPPED} if no day is left. */
    private static int[] upcomingPatterns(DayPatterns patterns, int todayEpochDay, DayPatterns.Builder upcoming) {
        int[] newPattern = new int[patterns.size()];
        for (int pattern = 0; pattern < patterns.size(); pattern++) {
            ServiceDays days = patterns.days(pattern).from(todayEpochDay);
            newPattern[pattern] = days.isEmpty() ? DROPPED : upcoming.intern(days);
        }
        return newPattern;
    }

    private static TimetableStore cut(TimetableStore store, TimetableStoreBuilder.Parts parts, int[] tripFirstEvent,
                                      IntList keptTrips, int[] newEvent, int[] newPattern,
                                      DayPatterns.Builder upcoming) {
        int eventCount = tripFirstEvent[keptTrips.size()];
        int[] evStation = keptEvents(parts.evStation(), newEvent, eventCount);
        int[] evDepSec = keptEvents(parts.evDepSec(), newEvent, eventCount);
        int[] evArrSec = keptEvents(parts.evArrSec(), newEvent, eventCount);
        int[] tripDayPattern = new int[keptTrips.size()];
        int[] tripRouteName = new int[keptTrips.size()];
        List<String> tripIds = new ArrayList<>(keptTrips.size());
        for (int newTrip = 0; newTrip < keptTrips.size(); newTrip++) {
            int trip = keptTrips.get(newTrip);
            tripDayPattern[newTrip] = newPattern[parts.tripDayPattern()[trip]];
            tripRouteName[newTrip] = parts.tripRouteName()[trip];
            tripIds.add(store.tripId(trip));
        }
        // Kept events keep their order, so the departures derived from them are the kept ones in the same order
        TimetableStoreBuilder.Parts kept = TimetableStoreBuilder.withDerivedDepartures(parts.stations(),
                tripFirstEvent, evStation, evDepSec, evArrSec, upcoming.build(), tripDayPattern, parts.routeNames(),
                tripRouteName, PackedStrings.of(tripIds));
        return TimetableStore.of(kept, keptRegularDepartures(store, newEvent, eventCount), store.stationCounts());
    }

    private static int[] keptEvents(int[] values, int[] newEvent, int eventCount) {
        int[] kept = new int[eventCount];
        for (int event = 0; event < values.length; event++) {
            if (newEvent[event] != DROPPED) kept[newEvent[event]] = values[event];
        }
        return kept;
    }

    private static long[] keptRegularDepartures(TimetableStore store, int[] newEvent, int eventCount) {
        long[] regular = new long[(eventCount + BITS_PER_WORD - 1) / BITS_PER_WORD];
        for (int event = 0; event < newEvent.length; event++) {
            if (newEvent[event] != DROPPED && store.isRegularDeparture(event)) {
                regular[newEvent[event] / BITS_PER_WORD] |= 1L << newEvent[event];
            }
        }
        return regular;
    }
}
