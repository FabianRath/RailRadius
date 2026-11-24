package com.railradius.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchRateLimiterTest {

    private static final String IP = "192.0.2.1";
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    // The costs of the biggest search (5 transfers, 360-min max wait) and of a 2-transfer search at a 180-min max wait
    private static final int BIG_COST = 87;
    private static final int TWO_TRANSFER_COST = 5;
    private static final int MAX_WAIT_MINUTES = 360;
    private static final int TABLE_WAIT_MINUTES = 180;

    private final AtomicLong nowNanos = new AtomicLong();

    @Test
    void searchOverTheBudgetIsTurnedAwayWithTheSecondsUntilItFits() {
        SearchRateLimiter limiter = new SearchRateLimiter(BIG_COST, 1, nowNanos::get);
        limiter.charge(IP, options(5, MAX_WAIT_MINUTES));
        nowNanos.addAndGet(2 * NANOS_PER_SECOND);

        assertThatThrownBy(() -> limiter.charge(IP, options(2, TABLE_WAIT_MINUTES)))
                .isInstanceOf(SearchRefusal.class)
                .hasMessageContaining("try again in 3 s");
    }

    @Test
    void budgetRefillsOverTime() {
        SearchRateLimiter limiter = new SearchRateLimiter(BIG_COST, 1, nowNanos::get);
        limiter.charge(IP, options(5, MAX_WAIT_MINUTES));
        nowNanos.addAndGet(TWO_TRANSFER_COST * NANOS_PER_SECOND);

        assertThatCode(() -> limiter.charge(IP, options(2, TABLE_WAIT_MINUTES))).doesNotThrowAnyException();
    }

    @Test
    void searchesWithMoreTransfersCostMore() {
        SearchRateLimiter limiter = new SearchRateLimiter(BIG_COST, 1, nowNanos::get);
        for (int i = 0; i < BIG_COST / TWO_TRANSFER_COST; i++) limiter.charge(IP, options(2, TABLE_WAIT_MINUTES));

        assertThatThrownBy(() -> limiter.charge(IP, options(2, TABLE_WAIT_MINUTES)))
                .isInstanceOf(SearchRefusal.class);
    }

    @Test
    void eachIpHasItsOwnBudget() {
        SearchRateLimiter limiter = new SearchRateLimiter(BIG_COST, 1, nowNanos::get);
        limiter.charge(IP, options(5, MAX_WAIT_MINUTES));

        assertThatCode(() -> limiter.charge("192.0.2.2", options(5, MAX_WAIT_MINUTES))).doesNotThrowAnyException();
    }

    @Test
    void ipv6AddressesInOneSlash64ShareABudget() {
        SearchRateLimiter limiter = new SearchRateLimiter(BIG_COST, 1, nowNanos::get);
        limiter.charge("2001:db8:1:2::1", options(5, MAX_WAIT_MINUTES));

        assertThatThrownBy(() -> limiter.charge("2001:db8:1:2::2", options(5, MAX_WAIT_MINUTES)))
                .isInstanceOf(SearchRefusal.class);
        assertThatCode(() -> limiter.charge("2001:db8:1:3::1", options(5, MAX_WAIT_MINUTES)))
                .doesNotThrowAnyException();
    }

    @Test
    void longerMaxWaitsAtChangesCostMore() {
        SearchRateLimiter limiter = new SearchRateLimiter(BIG_COST, 1, nowNanos::get);
        limiter.charge(IP, options(5, MAX_WAIT_MINUTES));

        assertThatThrownBy(() -> limiter.charge(IP, options(5, 0))).hasMessageContaining("try again in 43 s");
        assertThatThrownBy(() -> limiter.charge(IP, options(5, TABLE_WAIT_MINUTES)))
                .hasMessageContaining("try again in 65 s");
        assertThatThrownBy(() -> limiter.charge(IP, options(5, MAX_WAIT_MINUTES)))
                .hasMessageContaining("try again in 87 s");
    }

    @Test
    void budgetBelowTheBiggestSearchIsRejected() {
        assertThatThrownBy(() -> new SearchRateLimiter(BIG_COST - 1, 1, nowNanos::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SearchRateLimiter(BIG_COST, 1, nowNanos::get)).isNotNull();
    }

    private static SearchOptions options(int maxTransfers, int maxTransferMinutes) {
        return new SearchOptions("alpha:A1", maxTransfers, 0, maxTransferMinutes);
    }
}
