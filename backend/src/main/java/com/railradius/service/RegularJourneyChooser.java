package com.railradius.service;

import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Picks a station's journey among candidates with the same transfer count: the fastest one that runs on at least
 * half as many days as the candidate that runs most often. So a train that is a bit faster but runs on a few days
 * only doesn't hide the regular one (B26).
 */
final class RegularJourneyChooser {

    private static final int MIN_DAY_SHARE_DIVISOR = 2;

    private RegularJourneyChooser() {}

    /**
     * The chosen one of the candidate labels, which are not empty. A journey without calendar data
     * ({@link ServiceDays#EVERY_DAY}) has unknown days: it always runs often enough and sets no bar for the others.
     * Called for every station in every round, so it takes and returns plain ints (#41).
     */
    static int choose(int[] candidates, IntFunction<ServiceDays> daysOf, IntUnaryOperator durationMinutesOf) {
        if (candidates.length == 1) return candidates[0];
        int mostDays = 0;
        for (int candidate : candidates) {
            ServiceDays days = daysOf.apply(candidate);
            if (days != ServiceDays.EVERY_DAY) mostDays = Math.max(mostDays, days.dayCount());
        }
        int chosen = LabelArena.NONE;
        for (int candidate : candidates) {
            ServiceDays days = daysOf.apply(candidate);
            boolean runsOftenEnough = days == ServiceDays.EVERY_DAY
                    || days.dayCount() * MIN_DAY_SHARE_DIVISOR >= mostDays;
            boolean faster = chosen == LabelArena.NONE
                    || durationMinutesOf.applyAsInt(candidate) < durationMinutesOf.applyAsInt(chosen);
            if (runsOftenEnough && faster) chosen = candidate;
        }
        return chosen;
    }
}
