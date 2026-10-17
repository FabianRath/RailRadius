package com.railradius.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts failed admin logins per client IP, so a token guesser is turned away after a few attempts and every
 * attempt shows up in the log.
 */
@Component
@Slf4j
public class AdminLoginThrottle {

    private static final int MAX_FAILURES = 5;
    // Counted from the first failure: the count is created once and only incremented after that
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    // Bounds the memory when many IPs fail; past this, Caffeine evicts counts
    private static final int MAX_TRACKED_IPS = 10_000;

    private final Cache<String, AtomicInteger> failuresByIp = Caffeine.newBuilder()
            .expireAfterWrite(FAILURE_WINDOW)
            .maximumSize(MAX_TRACKED_IPS)
            .build();

    /** True once the IP has used up its failures in the current window. */
    public boolean isBlocked(String clientIp) {
        AtomicInteger failures = failuresByIp.getIfPresent(clientIp);
        return failures != null && failures.get() >= MAX_FAILURES;
    }

    /** Counts a failed login; logs the IP and count, never the token that was sent. */
    public void recordFailure(String clientIp) {
        int failures = failuresByIp.get(clientIp, ip -> new AtomicInteger()).incrementAndGet();
        log.warn("Failed admin login from {} ({} of {} allowed in {} min)",
                clientIp, failures, MAX_FAILURES, FAILURE_WINDOW.toMinutes());
    }
}
