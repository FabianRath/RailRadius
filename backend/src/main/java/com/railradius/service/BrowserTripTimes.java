package com.railradius.service;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The times of the browser file's trips (#49), lossless. Within a trip, each event's arrival and departure is the
 * difference to the trip's previous time, the first one to the trip's base: its first time. Many trips run the same
 * times at another hour, so such a trip profile is stored once, and each trip is its profile and its base. All numbers
 * are {@link VarInts}: a difference or base zigzag coded plus one, and 0 where the store has
 * {@link TimetableStore#NO_LEG}.
 * <p>
 * Blocks (bytes): each profile's stop count; the profiles' differences, arrival then departure per stop; each trip's
 * profile and base (0 for a trip without any time).
 */
final class BrowserTripTimes {

    // No real difference or time is this low
    private static final int NO_TIME = Integer.MIN_VALUE;
    private static final long NO_TIME_CODE = 0;

    /** Each event's arrival and departure seconds, as the store has them. */
    record Times(int[] evArrSec, int[] evDepSec) {}

    private BrowserTripTimes() {
    }

    static void write(TimetableStore store, BinaryBlocks.Writer out) throws IOException {
        Map<IntArrayKey, Integer> profiles = new HashMap<>();
        VarInts.Writer profileStopCounts = new VarInts.Writer();
        VarInts.Writer profileDifferences = new VarInts.Writer();
        VarInts.Writer tripCodes = new VarInts.Writer();
        for (int trip = 0; trip < store.tripCount(); trip++) {
            int firstEvent = store.firstEvent(trip);
            int stops = store.endEvent(trip) - firstEvent;
            int[] differences = new int[2 * stops];
            Arrays.fill(differences, NO_TIME);
            int base = NO_TIME;
            int previousSeconds = 0;
            for (int stop = 0; stop < stops; stop++) {
                int[] seconds = {store.arrivalSeconds(firstEvent + stop), store.departureSeconds(firstEvent + stop)};
                for (int side = 0; side < seconds.length; side++) {
                    if (seconds[side] == TimetableStore.NO_LEG) continue;
                    if (base == NO_TIME) {
                        base = seconds[side];
                        previousSeconds = base;
                    }
                    differences[2 * stop + side] = seconds[side] - previousSeconds;
                    previousSeconds = seconds[side];
                }
            }
            IntArrayKey key = new IntArrayKey(differences);
            Integer profile = profiles.get(key);
            if (profile == null) {
                profile = profiles.size();
                profiles.put(key, profile);
                profileStopCounts.add(stops);
                for (int difference : differences) profileDifferences.add(code(difference));
            }
            tripCodes.add(profile);
            tripCodes.add(code(base));
        }
        out.bytes(profileStopCounts.toArray());
        out.bytes(profileDifferences.toArray());
        out.bytes(tripCodes.toArray());
    }

    /** The times of the trips whose events start at tripFirstEvent, as {@link #write} wrote them. */
    static Times read(BinaryBlocks.Reader in, int[] tripFirstEvent) throws IOException {
        VarInts.Reader profileStopCounts = new VarInts.Reader(in.bytes());
        VarInts.Reader profileDifferences = new VarInts.Reader(in.bytes());
        VarInts.Reader tripCodes = new VarInts.Reader(in.bytes());
        IntList profileStart = new IntList();
        IntList differences = new IntList();
        profileStart.add(0);
        while (profileStopCounts.hasNext()) {
            int values = 2 * (int) profileStopCounts.next();
            for (int i = 0; i < values; i++) {
                differences.add(value(profileDifferences.next()));
            }
            profileStart.add(differences.size());
        }
        profileDifferences.checkAllRead();
        int tripCount = tripFirstEvent.length - 1;
        int[] evArrSec = new int[tripFirstEvent[tripCount]];
        int[] evDepSec = new int[evArrSec.length];
        for (int trip = 0; trip < tripCount; trip++) {
            int start = profileStart.get((int) tripCodes.next()) - 2 * tripFirstEvent[trip];
            // A trip without a time has no differences either, so its base is never read
            int previousSeconds = value(tripCodes.next());
            for (int event = tripFirstEvent[trip]; event < tripFirstEvent[trip + 1]; event++) {
                evArrSec[event] = seconds(differences.get(start + 2 * event), previousSeconds);
                if (evArrSec[event] != TimetableStore.NO_LEG) previousSeconds = evArrSec[event];
                evDepSec[event] = seconds(differences.get(start + 2 * event + 1), previousSeconds);
                if (evDepSec[event] != TimetableStore.NO_LEG) previousSeconds = evDepSec[event];
            }
        }
        tripCodes.checkAllRead();
        return new Times(evArrSec, evDepSec);
    }

    private static long code(int value) {
        return value == NO_TIME ? NO_TIME_CODE : VarInts.zigzag(value) + 1;
    }

    private static int value(long code) {
        return code == NO_TIME_CODE ? NO_TIME : VarInts.unzigzag(code - 1);
    }

    private static int seconds(int difference, int previousSeconds) {
        return difference == NO_TIME ? TimetableStore.NO_LEG : previousSeconds + difference;
    }
}
