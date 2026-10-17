package com.railradius.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSessionsTest {

    private final AdminSessions sessions = new AdminSessions();

    @Test
    void createdSessionIsLiveUntilInvalidated() {
        String id = sessions.create();

        assertThat(sessions.isLive(id)).isTrue();
        sessions.invalidate(id);
        assertThat(sessions.isLive(id)).isFalse();
    }

    @Test
    void unknownOrMissingIdIsNotLive() {
        sessions.create();

        assertThat(sessions.isLive("guess")).isFalse();
        assertThat(sessions.isLive(null)).isFalse();
    }

    @Test
    void idsAreRandomUrlSafeAndUnpadded() {
        String id = sessions.create();

        assertThat(id).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(sessions.create());
    }
}
