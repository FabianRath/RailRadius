package com.railradius.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * Random ids of the admin sessions started by {@code POST /api/admin/session}, so the admin cookie carries one of
 * these instead of the token itself. Held in memory only: a restart logs every browser out of its rate-limit
 * exemption.
 */
@Component
public class AdminSessions {

    private static final Duration SESSION_LIFETIME = Duration.ofHours(12);
    // Only the few admins log in; past this, Caffeine evicts sessions to bound the memory
    private static final int MAX_SESSIONS = 100;
    private static final int ID_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Cache<String, Boolean> sessions = Caffeine.newBuilder()
            .expireAfterWrite(SESSION_LIFETIME)
            .maximumSize(MAX_SESSIONS)
            .build();

    /** Starts a session and returns its id (base64url, no padding). */
    public String create() {
        byte[] bytes = new byte[ID_BYTES];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(id, Boolean.TRUE);
        return id;
    }

    public boolean isLive(String id) {
        return id != null && sessions.getIfPresent(id) != null;
    }

    public void invalidate(String id) {
        if (id != null) sessions.invalidate(id);
    }
}
