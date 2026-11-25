package com.railradius.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class TransferWindowIntsTest {

    private static final long MONDAY = 1L;
    private static final long TUESDAY = 2L;
    private static final int SAME_DAY = 0;
    private static final int DEPARTURE_EVENT = 1;
    private static final int DEPARTURE_SECONDS = 400;
    private static final int MAX_WAIT_SECONDS = 1000;
    private static final long SEED = 39;
    private static final int RANDOM_WINDOWS = 200;
    // Dense enough for whole blocks of 32 arrivals within one wait range
    private static final int RANDOM_ARRIVALS = 300;
    private static final int RANDOM_DEPARTURES = 300;
    private static final int RANDOM_SPAN_SECONDS = 4_000;
    private static final int RANDOM_LONGEST_JOURNEY_SECONDS = 2_000;
    // Few distinct starts make equal starts common; three day bits make arrivals that lack the day common
    private static final int RANDOM_START_STEP_SECONDS = 60;
    private static final int RANDOM_DAY_BITS = 3;
    // Several large blocks of 1,024 arrivals within one wait range, where a wanted day is rare enough that whole
    // large blocks lack it
    private static final int LARGE_WINDOWS = 20;
    private static final int LARGE_ARRIVALS = 12_000;
    private static final int LARGE_DEPARTURES = 200;
    private static final int RARE_DAY_ONE_IN = 3_000;

    // The departure runs on Monday. Arrival 2 starts latest but runs on Tuesday only; of the Monday arrivals,
    // 0 and 3 start equally late and 1 later than both: the scan must still pick 1
    @Test
    void theLatestStartingArrivalThatAddsTheDayIsChosenWhenTheBestLacksIt() {
        TransferWindowInts window = windowOver(new int[] {100, 200, 300, 350}, new int[] {100, 150, 250, 100},
                new long[] {MONDAY, MONDAY, TUESDAY, MONDAY});

        assertThat(window.bestFor(DEPARTURE_EVENT, DEPARTURE_SECONDS, MONDAY, SAME_DAY, 0)).isEqualTo(1);
    }

    // Arrivals 0 and 3 start equally late and run on Monday, 2 starts later on Tuesday only: of equal starts the
    // first wins, though only the later one is still in the window's queue
    @Test
    void theFirstOfEquallyLateStartingArrivalsThatAddTheDayIsChosen() {
        TransferWindowInts window = windowOver(new int[] {100, 200, 300, 350}, new int[] {100, 90, 250, 100},
                new long[] {MONDAY, MONDAY, TUESDAY, MONDAY});

        assertThat(window.bestFor(DEPARTURE_EVENT, DEPARTURE_SECONDS, MONDAY, SAME_DAY, 0)).isEqualTo(0);
    }

    // Over three large blocks: the head 1,500 starts latest but runs on Tuesday; 2,100 is the first queued Monday
    // arrival. 500, in the first large block, starts as late as 2,100 and comes first, so it wins the tie: that large
    // block's latest start equals the bound's start and must not be skipped
    @Test
    void anEquallyLateArrivalSeveralLargeBlocksBeforeTheBoundIsChosen() {
        int count = 2_200;
        int[] arrivalSeconds = new int[count];
        int[] startSeconds = new int[count];
        long[] days = new long[count];
        for (int i = 0; i < count; i++) {
            arrivalSeconds[i] = i / 10;
            startSeconds[i] = -1_000;
            days[i] = TUESDAY;
        }
        startSeconds[500] = 0;
        days[500] = MONDAY;
        startSeconds[1_500] = 100;
        startSeconds[2_100] = 0;
        days[2_100] = MONDAY;
        TransferWindowInts window = windowOver(arrivalSeconds, startSeconds, days);

        assertThat(window.bestFor(DEPARTURE_EVENT, DEPARTURE_SECONDS, MONDAY, SAME_DAY, 0)).isEqualTo(500);
    }

    @Test
    void noArrivalIsChosenWhenNoneAddsTheDay() {
        TransferWindowInts window = windowOver(new int[] {100, 200}, new int[] {100, 150},
                new long[] {TUESDAY, TUESDAY});

        assertThat(window.bestFor(DEPARTURE_EVENT, DEPARTURE_SECONDS, MONDAY, SAME_DAY, 0)).isEqualTo(-1);
    }

    // The answer as the search semantics contract states it: the arrival in the wait range with the latest start,
    // the last of equal ones, if it adds the day; else, of those that add it, the latest start, the first of equal ones
    @Test
    void randomWindowsChooseWhatAFullScanChooses() {
        Random random = new Random(SEED);
        for (int window = 0; window < RANDOM_WINDOWS; window++) {
            assertChoosesWhatAFullScanChooses(random, window, RANDOM_ARRIVALS, RANDOM_DEPARTURES,
                    () -> random.nextInt(1 << RANDOM_DAY_BITS));
        }
    }

    // Most arrivals run on Tuesday only, so the scan for a Monday one crosses whole large blocks without it (#42)
    @Test
    void windowsSpanningSeveralLargeBlocksChooseWhatAFullScanChooses() {
        Random random = new Random(SEED);
        for (int window = 0; window < LARGE_WINDOWS; window++) {
            assertChoosesWhatAFullScanChooses(random, window, LARGE_ARRIVALS, LARGE_DEPARTURES,
                    () -> random.nextInt(RARE_DAY_ONE_IN) == 0 ? MONDAY : TUESDAY);
        }
    }

    private static void assertChoosesWhatAFullScanChooses(Random random, int window, int arrivalCount,
                                                          int departureCount, LongSupplier randomDays) {
        int[] arrivalSeconds = new int[arrivalCount];
        int[] startSeconds = new int[arrivalCount];
        long[] days = new long[arrivalCount];
        for (int i = 0; i < arrivalCount; i++) {
            arrivalSeconds[i] = random.nextInt(RANDOM_SPAN_SECONDS);
        }
        Arrays.sort(arrivalSeconds);
        for (int i = 0; i < arrivalCount; i++) {
            int journeySteps = random.nextInt(RANDOM_LONGEST_JOURNEY_SECONDS / RANDOM_START_STEP_SECONDS);
            startSeconds[i] = arrivalSeconds[i] / RANDOM_START_STEP_SECONDS * RANDOM_START_STEP_SECONDS
                    - journeySteps * RANDOM_START_STEP_SECONDS;
            days[i] = randomDays.getAsLong();
        }
        TransferWindowInts transferWindow = windowOver(arrivalSeconds, startSeconds, days);
        int[] departures = random.ints(departureCount, 0, RANDOM_SPAN_SECONDS).sorted().toArray();
        for (int departureSeconds : departures) {
            long wanted = 1 + random.nextInt((1 << RANDOM_DAY_BITS) - 1);
            assertThat(transferWindow.bestFor(DEPARTURE_EVENT, departureSeconds, wanted, SAME_DAY, 0))
                    .as("window %d, departure %d", window, departureSeconds)
                    .isEqualTo(fullScan(arrivalSeconds, startSeconds, days, departureSeconds, wanted));
        }
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
        int blocks = TransferWindowInts.Arrivals.blockCount(count);
        int largeBlocks = TransferWindowInts.Arrivals.largeBlockCount(count);
        TransferWindowInts.Arrivals arrivals = new TransferWindowInts.Arrivals(count, arrivalSeconds, startSeconds,
                days, new int[blocks], new long[blocks], new int[largeBlocks], new long[largeBlocks]);
        arrivals.summarizeBlocks();
        return new TransferWindowInts(arrivals, new int[count], 0, 0, MAX_WAIT_SECONDS);
    }
}
