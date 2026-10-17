package com.railradius.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.railradius.util.ClientKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * A search budget per client ({@link ClientKeys#rateLimitKey}, so IPv6 per /64) that refills over time (token
 * bucket). A search costs more the more transfers it has, since that is what makes it slow; one served from the
 * result cache is free (see {@link ReachabilityService}).
 */
@Component
@Slf4j
public class SearchRateLimiter {

    // A search's p50 time on the full data per max transfers, in units of about 100 ms, at least 1 (docs/HOSTING.md,
    // "Search time per transfer count"). More transfers cost as much as 5
    private static final int[] COST_BY_MAX_TRANSFERS = {1, 1, 5, 17, 40, 65};
    private static final double NANOS_PER_SECOND = 1e9;

    /** Who asks for a search; admins are never limited. */
    public record Client(String ip, boolean admin) {}

    /** Thrown when the client's budget is too low for the search. */
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public static class TooManySearchesException extends RuntimeException {
        TooManySearchesException(long waitSeconds) {
            super("Too many searches, please try again in " + waitSeconds + " s");
        }
    }

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
        if (capacity < costOf(Integer.MAX_VALUE)) {
            throw new IllegalArgumentException("search.rate-limit.capacity " + capacity
                    + " is below the cost of the biggest search, " + costOf(Integer.MAX_VALUE));
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
     * Takes the cost of a search with this many transfers from the client's budget.
     *
     * @throws TooManySearchesException if the budget is too low; nothing is taken then
     */
    public void charge(String clientIp, int maxTransfers) {
        int cost = costOf(maxTransfers);
        long waitSeconds = buckets.get(ClientKeys.rateLimitKey(clientIp), clientKey -> new Bucket()).take(cost);
        if (waitSeconds > 0) {
            log.info("Search from {} turned away by the rate limit, {} s until it fits", clientIp, waitSeconds);
            throw new TooManySearchesException(waitSeconds);
        }
    }

    private static int costOf(int maxTransfers) {
        return COST_BY_MAX_TRANSFERS[Math.clamp(maxTransfers, 0, COST_BY_MAX_TRANSFERS.length - 1)];
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
