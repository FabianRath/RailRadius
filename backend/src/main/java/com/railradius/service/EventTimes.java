package com.railradius.service;

import java.util.Arrays;

/**
 * Each stop event's departure and arrival seconds, as its trip's earliest time plus 16-bit offsets from it (#38): two
 * chars per event instead of two ints, and still every second. An event whose time lies further from its trip's
 * earliest one than an offset holds (a trip of over ~18 h, or one whose times wrap past midnight) keeps both of its
 * times whole in a short list. Times without a leg ({@link TimetableStore#NO_LEG}) stay so.
 */
final class EventTimes {

    // Offsets up to MAX_OFFSET_SECONDS are times; the two chars above it mark an event without a leg or a whole time
    private static final char NO_LEG_OFFSET = Character.MAX_VALUE;
    private static final char WHOLE_OFFSET = Character.MAX_VALUE - 1;
    private static final int MAX_OFFSET_SECONDS = WHOLE_OFFSET - 1;

    private final int[] evTrip;
    private final int[] tripBaseSeconds;
    private final char[] departureOffsets;
    private final char[] arrivalOffsets;
    // The events that keep whole times, ascending, and their times
    private final int[] wholeEvents;
    private final int[] wholeDepartureSeconds;
    private final int[] wholeArrivalSeconds;

    private EventTimes(int[] evTrip, int[] tripBaseSeconds, char[] departureOffsets,
                       char[] arrivalOffsets, int[] wholeEvents, int[] wholeDepartureSeconds,
                       int[] wholeArrivalSeconds) {
        this.evTrip = evTrip;
        this.tripBaseSeconds = tripBaseSeconds;
        this.departureOffsets = departureOffsets;
        this.arrivalOffsets = arrivalOffsets;
        this.wholeEvents = wholeEvents;
        this.wholeDepartureSeconds = wholeDepartureSeconds;
        this.wholeArrivalSeconds = wholeArrivalSeconds;
    }

    /** The times of events numbered as in tripFirstEvent, with evTrip the trip of each event. */
    static EventTimes of(int[] tripFirstEvent, int[] evTrip, int[] evDepSec, int[] evArrSec) {
        int tripCount = tripFirstEvent.length - 1;
        int[] tripBaseSeconds = new int[tripCount];
        char[] departureOffsets = new char[evDepSec.length];
        char[] arrivalOffsets = new char[evArrSec.length];
        IntList wholeEvents = new IntList();
        for (int trip = 0; trip < tripCount; trip++) {
            int baseSeconds = earliestSeconds(tripFirstEvent[trip], tripFirstEvent[trip + 1], evDepSec, evArrSec);
            tripBaseSeconds[trip] = baseSeconds;
            for (int event = tripFirstEvent[trip]; event < tripFirstEvent[trip + 1]; event++) {
                if (fits(evDepSec[event], baseSeconds) && fits(evArrSec[event], baseSeconds)) {
                    departureOffsets[event] = offset(evDepSec[event], baseSeconds);
                    arrivalOffsets[event] = offset(evArrSec[event], baseSeconds);
                } else {
                    departureOffsets[event] = WHOLE_OFFSET;
                    arrivalOffsets[event] = WHOLE_OFFSET;
                    wholeEvents.add(event);
                }
            }
        }
        int[] whole = wholeEvents.toArray();
        int[] wholeDepartures = new int[whole.length];
        int[] wholeArrivals = new int[whole.length];
        for (int i = 0; i < whole.length; i++) {
            wholeDepartures[i] = evDepSec[whole[i]];
            wholeArrivals[i] = evArrSec[whole[i]];
        }
        return new EventTimes(evTrip, tripBaseSeconds, departureOffsets, arrivalOffsets, whole,
                wholeDepartures, wholeArrivals);
    }

    int departureSeconds(int event) {
        char offset = departureOffsets[event];
        if (offset <= MAX_OFFSET_SECONDS) return tripBaseSeconds[evTrip[event]] + offset;
        return offset == NO_LEG_OFFSET ? TimetableStore.NO_LEG : wholeDepartureSeconds[wholeIndex(event)];
    }

    int arrivalSeconds(int event) {
        char offset = arrivalOffsets[event];
        if (offset <= MAX_OFFSET_SECONDS) return tripBaseSeconds[evTrip[event]] + offset;
        return offset == NO_LEG_OFFSET ? TimetableStore.NO_LEG : wholeArrivalSeconds[wholeIndex(event)];
    }

    /** How many events keep whole times, for the build log. */
    int wholeCount() {
        return wholeEvents.length;
    }

    int[] departuresToInts() {
        int[] seconds = new int[departureOffsets.length];
        for (int event = 0; event < seconds.length; event++) {
            seconds[event] = departureSeconds(event);
        }
        return seconds;
    }

    int[] arrivalsToInts() {
        int[] seconds = new int[arrivalOffsets.length];
        for (int event = 0; event < seconds.length; event++) {
            seconds[event] = arrivalSeconds(event);
        }
        return seconds;
    }

    /** Bytes of the arrays, the event trips aside: the store holds them for its own reads. */
    long sizeBytes() {
        return (long) (departureOffsets.length + arrivalOffsets.length) * Character.BYTES
                + (long) (tripBaseSeconds.length + wholeEvents.length * 3) * Integer.BYTES;
    }

    private int wholeIndex(int event) {
        return Arrays.binarySearch(wholeEvents, event);
    }

    /** The trip's earliest time; 0 for a trip without any, whose events then all have no leg. */
    private static int earliestSeconds(int firstEvent, int endEvent, int[] evDepSec, int[] evArrSec) {
        int earliest = Integer.MAX_VALUE;
        for (int event = firstEvent; event < endEvent; event++) {
            if (evDepSec[event] != TimetableStore.NO_LEG) earliest = Math.min(earliest, evDepSec[event]);
            if (evArrSec[event] != TimetableStore.NO_LEG) earliest = Math.min(earliest, evArrSec[event]);
        }
        return earliest == Integer.MAX_VALUE ? 0 : earliest;
    }

    private static boolean fits(int seconds, int baseSeconds) {
        return seconds == TimetableStore.NO_LEG
                || (seconds >= baseSeconds && seconds - baseSeconds <= MAX_OFFSET_SECONDS);
    }

    private static char offset(int seconds, int baseSeconds) {
        return seconds == TimetableStore.NO_LEG ? NO_LEG_OFFSET : (char) (seconds - baseSeconds);
    }
}
