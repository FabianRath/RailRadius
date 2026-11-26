package com.railradius.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TransferWindowIntsTest {

    private static final long MONDAY = 1L;
    private static final long TUESDAY = 2L;
    private static final int SAME_DAY = 0;
    private static final int DEPARTURE_SECONDS = 400;
    private static final int MAX_WAIT_SECONDS = 1000;
    private static final long SEED = 39;
    private static final int RANDOM_WINDOWS = 200;
    // Dense enough for many arrivals within one wait range
    private static final int RANDOM_ARRIVALS = 300;
    private static final int RANDOM_DEPARTURES = 300;
    private static final int RANDOM_SPAN_SECONDS = 4_000;
    private static final int RANDOM_LONGEST_JOURNEY_SECONDS = 2_000;
    // Few distinct starts make equal starts common; three day bits make arrivals that lack the day common
    private static final int RANDOM_START_STEP_SECONDS = 60;
    private static final int RANDOM_DAY_BITS = 3;
    // More day bits make a departure ask again for the days left after its first answers (#82)
    private static final int REPEAT_DAY_BITS = 6;

    // The departure runs on Monday. Arrival 2 starts latest but runs on Tuesday only; of the Monday arrivals,
    // 0 and 3 start equally late and 1 later than both: the fallback must still pick 1
    @Test
    void theLatestStartingArrivalThatAddsTheDayIsChosenWhenTheBestLacksIt() {
        TransferWindowInts window = windowOver(new int[] {100, 200, 300, 350}, new int[] {100, 150, 250, 100},
                new long[] {MONDAY, MONDAY, TUESDAY, MONDAY});

        assertThat(window.bestFor(DEPARTURE_SECONDS, MONDAY, 0)).isEqualTo(1);
    }

    // Arrivals 0 and 3 start equally late and run on Monday, 2 starts later on Tuesday only: of equal starts the
    // first wins, though only the later one is still in the window's queue
    @Test
    void theFirstOfEquallyLateStartingArrivalsThatAddTheDayIsChosen() {
        TransferWindowInts window = windowOver(new int[] {100, 200, 300, 350}, new int[] {100, 90, 250, 100},
                new long[] {MONDAY, MONDAY, TUESDAY, MONDAY});

        assertThat(window.bestFor(DEPARTURE_SECONDS, MONDAY, 0)).isEqualTo(0);
    }

    @Test
    void noArrivalIsChosenWhenNoneAddsTheDay() {
        TransferWindowInts window = windowOver(new int[] {100, 200}, new int[] {100, 150},
                new long[] {TUESDAY, TUESDAY});

        assertThat(window.bestFor(DEPARTURE_SECONDS, MONDAY, 0)).isEqualTo(-1);
    }

    // The answer as the search semantics contract states it: the arrival in the wait range with the latest start,
    // the last of equal ones, if it adds the day; else, of those that add it, the latest start, the first of equal ones
    @Test
    void randomWindowsChooseWhatAFullScanChooses() {
        Random random = new Random(SEED);
        for (int window = 0; window < RANDOM_WINDOWS; window++) {
            RandomArrivals arrivals = randomArrivals(random, RANDOM_DAY_BITS);
            TransferWindowInts transferWindow = arrivals.window();
            int[] departures = random.ints(RANDOM_DEPARTURES, 0, RANDOM_SPAN_SECONDS).sorted().toArray();
            for (int departureSeconds : departures) {
                long wanted = 1 + random.nextInt((1 << RANDOM_DAY_BITS) - 1);
                assertThat(transferWindow.bestFor(departureSeconds, wanted, 0))
                        .as("window %d, departure %d", window, departureSeconds)
                        .isEqualTo(arrivals.fullScan(departureSeconds, wanted));
            }
        }
    }

    // As the boarding asks: again for the days its answers left out, until none is left or no arrival adds one
    @Test
    void randomRepeatedAsksForTheDaysLeftChooseWhatAFullScanChooses() {
        Random random = new Random(SEED);
        for (int window = 0; window < RANDOM_WINDOWS; window++) {
            RandomArrivals arrivals = randomArrivals(random, REPEAT_DAY_BITS);
            TransferWindowInts transferWindow = arrivals.window();
            int[] departures = random.ints(RANDOM_DEPARTURES, 0, RANDOM_SPAN_SECONDS).sorted().toArray();
            for (int departureSeconds : departures) {
                long tripDays = 1 + random.nextInt((1 << REPEAT_DAY_BITS) - 1);
                long served = 0;
                while ((tripDays & ~served) != 0) {
                    int expected = arrivals.fullScan(departureSeconds, tripDays & ~served);
                    assertThat(transferWindow.bestFor(departureSeconds, tripDays, served))
                            .as("window %d, departure %d, served %d", window, departureSeconds, served)
                            .isEqualTo(expected);
                    if (expected < 0) break;
                    served |= tripDays & arrivals.days()[expected];
                }
            }
        }
    }

    private record RandomArrivals(int[] arrivalSeconds, int[] startSeconds, long[] days) {

        TransferWindowInts window() {
            return windowOver(arrivalSeconds, startSeconds, days);
        }

        int fullScan(int departureSeconds, long wanted) {
            return TransferWindowIntsTest.fullScan(arrivalSeconds, startSeconds, days, departureSeconds, wanted);
        }
    }

    private static RandomArrivals randomArrivals(Random random, int dayBits) {
        int[] arrivalSeconds = new int[RANDOM_ARRIVALS];
        int[] startSeconds = new int[RANDOM_ARRIVALS];
        long[] days = new long[RANDOM_ARRIVALS];
        for (int i = 0; i < RANDOM_ARRIVALS; i++) {
            arrivalSeconds[i] = random.nextInt(RANDOM_SPAN_SECONDS);
        }
        Arrays.sort(arrivalSeconds);
        for (int i = 0; i < RANDOM_ARRIVALS; i++) {
            int journeySteps = random.nextInt(RANDOM_LONGEST_JOURNEY_SECONDS / RANDOM_START_STEP_SECONDS);
            startSeconds[i] = arrivalSeconds[i] / RANDOM_START_STEP_SECONDS * RANDOM_START_STEP_SECONDS
                    - journeySteps * RANDOM_START_STEP_SECONDS;
            days[i] = random.nextInt(1 << dayBits);
        }
        return new RandomArrivals(arrivalSeconds, startSeconds, days);
    }

    private static int fullScan(int[] arrivalSeconds, int[] startSeconds, long[] days, int departureSeconds,
                                long wanted) {
        int latest = -1;
        int best = -1;
        for (int i = 0; i < arrivalSeconds.length; i++) {
            if (arrivalSeconds[i] > departureSeconds || arrivalSeconds[i] < departureSeconds - MAX_WAIT_SECONDS) {
                continue;
            }
            if (latest < 0 || startSeconds[i] >= startSeconds[latest]) latest = i;
            if ((days[i] & wanted) != 0 && (best < 0 || startSeconds[i] > startSeconds[best])) best = i;
        }
        return latest >= 0 && (days[latest] & wanted) != 0 ? latest : best;
    }

    private static TransferWindowInts windowOver(int[] arrivalSeconds, int[] startSeconds, long[] days) {
        int count = arrivalSeconds.length;
        TransferWindowInts.Arrivals arrivals = TransferWindowInts.Arrivals.ofCapacity(count);
        System.arraycopy(arrivalSeconds, 0, arrivals.arrivalSeconds(), 0, count);
        System.arraycopy(startSeconds, 0, arrivals.startSeconds(), 0, count);
        System.arraycopy(days, 0, arrivals.days(), 0, count);
        return new TransferWindowInts(arrivals, new int[count], SAME_DAY, 0, MAX_WAIT_SECONDS);
    }
}
