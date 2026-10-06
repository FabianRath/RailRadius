package com.railreach.service;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Picks a station's journey among candidates with the same transfer count: the fastest one that runs on at least
 * half as many days as the candidate that runs most often. So a train that is a bit faster but runs on a few days
 * only doesn't hide the regular one (B26).
 */
final class RegularJourneyChooser {

    private static final int MIN_DAY_SHARE_DIVISOR = 2;

    private RegularJourneyChooser() {}

    /**
     * {@code candidates} is not empty. A journey without calendar data ({@link ServiceDays#EVERY_DAY}) has unknown
     * days: it always runs often enough and sets no bar for the others.
     */
    static <T> T choose(List<T> candidates, Function<T, ServiceDays> daysOf, ToIntFunction<T> durationMinutesOf) {
        if (candidates.size() == 1) return candidates.getFirst();
        List<ServiceDays> days = candidates.stream().map(daysOf).toList();
        int mostDays = days.stream()
                .filter(journeyDays -> journeyDays != ServiceDays.EVERY_DAY)
                .mapToInt(ServiceDays::dayCount)
                .max()
                .orElse(0);
        T chosen = null;
        for (int i = 0; i < candidates.size(); i++) {
            T candidate = candidates.get(i);
            boolean runsOftenEnough = days.get(i) == ServiceDays.EVERY_DAY
                    || days.get(i).dayCount() * MIN_DAY_SHARE_DIVISOR >= mostDays;
            boolean faster = chosen == null
                    || durationMinutesOf.applyAsInt(candidate) < durationMinutesOf.applyAsInt(chosen);
            if (runsOftenEnough && faster) chosen = candidate;
        }
        return chosen;
    }
}
