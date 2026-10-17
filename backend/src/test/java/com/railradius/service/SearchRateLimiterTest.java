package com.railradius.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchRateLimiterTest {

    private static final String IP = "192.0.2.1";
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    // The costs of a 5- and a 2-transfer search
    private static final int BIG_COST = 65;
    private static final int TWO_TRANSFER_COST = 5;

    private final AtomicLong nowNanos = new AtomicLong();

    @Test
    void searchOverTheBudgetIsTurnedAwayWithTheSecondsUntilItFits() {
        SearchRateLimiter limiter = new SearchRateLimiter(BIG_COST, 1, nowNanos::get);
        limiter.charge(IP, 5);
        nowNanos.addAndGet(2 * NANOS_PER_SECOND);

        assertThatThrownBy(() -> limiter.charge(IP, 2))
                .isInstanceOf(SearchRateLimiter.TooManySearchesException.class)
                .hasMessageContaining("try again in 3 s");
    }

    @Test
    void budgetRefillsOverTime() {
        SearchRateLimiter limiter = new SearchRateLimiter(BIG_COST, 1, nowNanos::get);
        limiter.charge(IP, 5);
        nowNanos.addAndGet(TWO_TRANSFER_COST * NANOS_PER_SECOND);

        assertThatCode(() -> limiter.charge(IP, 2)).doesNotThrowAnyException();
    }

    @Test
    void searchesWithMoreTransfersCostMore() {
        SearchRateLimiter limiter = new SearchRateLimiter(BIG_COST, 1, nowNanos::get);
        for (int i = 0; i < BIG_COST / TWO_TRANSFER_COST; i++) limiter.charge(IP, 2);

        assertThatThrownBy(() -> limiter.charge(IP, 2))
                .isInstanceOf(SearchRateLimiter.TooManySearchesException.class);
    }

    @Test
    void eachIpHasItsOwnBudget() {
        SearchRateLimiter limiter = new SearchRateLimiter(BIG_COST, 1, nowNanos::get);
        limiter.charge(IP, 5);

        assertThatCode(() -> limiter.charge("192.0.2.2", 5)).doesNotThrowAnyException();
    }

    @Test
    void ipv6AddressesInOneSlash64ShareABudget() {
        SearchRateLimiter limiter = new SearchRateLimiter(BIG_COST, 1, nowNanos::get);
        limiter.charge("2001:db8:1:2::1", 5);

        assertThatThrownBy(() -> limiter.charge("2001:db8:1:2::2", 5))
                .isInstanceOf(SearchRateLimiter.TooManySearchesException.class);
        assertThatCode(() -> limiter.charge("2001:db8:1:3::1", 5)).doesNotThrowAnyException();
    }

    @Test
    void transferCountsAboveFiveCostAsMuchAsFive() {
        SearchRateLimiter limiter = new SearchRateLimiter(BIG_COST, 1, nowNanos::get);

        assertThatCode(() -> limiter.charge(IP, 9)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.charge(IP, 0))
                .isInstanceOf(SearchRateLimiter.TooManySearchesException.class);
    }

    @Test
    void budgetBelowTheBiggestSearchIsRejected() {
        assertThatThrownBy(() -> new SearchRateLimiter(BIG_COST - 1, 1, nowNanos::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SearchRateLimiter(BIG_COST, 1, nowNanos::get)).isNotNull();
    }
}
