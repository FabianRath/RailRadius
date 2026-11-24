package com.railradius.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.railradius.util.ClientKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * A search budget per client ({@link ClientKeys#rateLimitKey}, so IPv6 per /64) that refills over time (token
 * bucket). A search costs more the more transfers and the longer a max wait at changes it has, since that is what
 * makes it slow; one served from the result cache is free (see {@link SearchAdmission}).
 */
@Component
@Slf4j
public class SearchRateLimiter {

    // A search's p50 time on the full data per max transfers at a 0-180 min wait at changes, in units of about 100 ms
    // of the engine before S18, at least 1 (docs/HOSTING.md, "Baselines (S13)"). Today's engine is faster but has the
    // same shape (S34). One entry per allowed max transfers, 0 to SearchOptions.MAX_TRANSFERS
    private static final int[] COST_BY_MAX_TRANSFERS = {1, 1, 5, 17, 40, 65};
    // The time grows about linearly with the max wait at changes (S34): the cost scales with the max wait plus this, so
    // a 0-min max wait costs 2/3 of the table and the 360-min maximum 4/3
    private static final int WAIT_COST_OFFSET_MINUTES = 360;
    private static final int TABLE_WAIT_MINUTES = 180;
    private static final double NANOS_PER_SECOND = 1e9;
    // A journey from a ride list reads only the few trips it rides
    private static final int RIDE_LIST_JOURNEY_COST = 1;

    private final int capacity;
    private final double refillPerSecond;
    private final LongSupplier nanoTime;
    private final Cache<String, Bucket> buckets;

    @Autowired
    public SearchRateLimiter(@Value("${railradius.search.rate-limit.capacity:200}") int capacity,
                             @Value("${railradius.search.rate-limit.refill-per-second:1}") double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    SearchRateLimiter(int capacity, double refillPerSecond, LongSupplier nanoTime) {
        int biggestCost = costOf(SearchOptions.MAX_TRANSFERS, SearchOptions.MAX_TRANSFER_WAIT_MINUTES);
        if (capacity < biggestCost) {
            throw new IllegalArgumentException("search.rate-limit.capacity " + capacity
                    + " is below the cost of the biggest search, " + biggestCost);
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.nanoTime = nanoTime;
        // A bucket idle this long is full again, so dropping it changes nothing
        long refillSeconds = (long) Math.ceil(capacity / refillPerSecond);
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofSeconds(refillSeconds))
                .ticker(nanoTime::getAsLong)
                .build();
    }

    /**
     * Takes the cost of a search, by its max transfers and max wait at changes, from the client's budget.
     *
     * @throws SearchRefusal if the budget is too low; nothing is taken then
     */
    public void charge(String clientIp, SearchOptions options) {
        take(clientIp, costOf(options.maxTransfers(), options.maxTransferMins()));
    }

    /**
     * Takes the cost of building a journey from a browser's ride list ({@link RideListJourneys}) from the client's
     * budget, so forged lists can't make the server do much work.
     *
     * @throws SearchRefusal if the budget is too low; nothing is taken then
     */
    public void chargeRideListJourney(String clientIp) {
        take(clientIp, RIDE_LIST_JOURNEY_COST);
    }

    private void take(String clientIp, int cost) {
        long waitSeconds = buckets.get(ClientKeys.rateLimitKey(clientIp), clientKey -> new Bucket()).take(cost);
        if (waitSeconds > 0) {
            log.info("Request turned away by the rate limit, {} s until it fits", waitSeconds);
            throw SearchRefusal.tooManySearches(waitSeconds);
        }
    }

    private static int costOf(int maxTransfers, int maxTransferMinutes) {
        int tableCost = COST_BY_MAX_TRANSFERS[maxTransfers];
        double waitFactor = (double) (maxTransferMinutes + WAIT_COST_OFFSET_MINUTES)
                / (TABLE_WAIT_MINUTES + WAIT_COST_OFFSET_MINUTES);
        return Math.max(1, (int) Math.round(tableCost * waitFactor));
    }

    private final class Bucket {

        private double tokens = capacity;
        private long updatedNanos = nanoTime.getAsLong();

        /** Takes the cost and returns 0, or returns the seconds until the budget covers it. */
        synchronized long take(int cost) {
            long nowNanos = nanoTime.getAsLong();
            tokens = Math.min(capacity, tokens + (nowNanos - updatedNanos) / NANOS_PER_SECOND * refillPerSecond);
            updatedNanos = nowNanos;
            if (tokens >= cost) {
                tokens -= cost;
                return 0;
            }
            return (long) Math.ceil((cost - tokens) / refillPerSecond);
        }
    }
}
