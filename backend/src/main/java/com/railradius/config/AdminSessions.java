package com.railradius.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * The admin sessions started by {@code POST /api/admin/session} and their cookie, which carries a random session id
 * instead of the token itself. Held in memory only: a restart logs every browser out of its rate-limit exemption.
 */
@Component
public class AdminSessions {

    public static final String COOKIE = "railradius_admin";
    // All of /api, so that logout (DELETE /api/admin/session) can name the session it ends
    private static final String COOKIE_PATH = "/api";
    // Dev runs on plain-HTTP localhost, where curl and some browsers (Safari) drop a Secure cookie
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private static final Duration SESSION_LIFETIME = Duration.ofHours(12);
    // Only the few admins log in; past this, Caffeine evicts sessions to bound the memory
    private static final int MAX_SESSIONS = 100;
    private static final int ID_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Cache<String, Boolean> sessions = Caffeine.newBuilder()
            .expireAfterWrite(SESSION_LIFETIME)
            .maximumSize(MAX_SESSIONS)
            .build();

    /**
     * Starts a session and returns its cookie (id in base64url, no padding), sent to /api and lasting until the
     * browser closes. Secure except on localhost: {@code request.isSecure()} is false behind a TLS proxy.
     */
    public ResponseCookie start(HttpServletRequest request) {
        byte[] bytes = new byte[ID_BYTES];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(id, Boolean.TRUE);
        return cookieBuilder(id, request).build();
    }

    /** Ends the session named in the request's cookie, if any, and returns the cookie that deletes it. */
    public ResponseCookie end(HttpServletRequest request) {
        sessionId(request).ifPresent(sessions::invalidate);
        return cookieBuilder("", request).maxAge(Duration.ZERO).build();
    }

    /** True if the request's cookie names a session that has not ended or expired. */
    public boolean isLive(HttpServletRequest request) {
        return sessionId(request).map(sessions::getIfPresent).isPresent();
    }

    private static Optional<String> sessionId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies).filter(cookie -> COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue).findFirst();
    }

    private static ResponseCookie.ResponseCookieBuilder cookieBuilder(String value, HttpServletRequest request) {
        return ResponseCookie.from(COOKIE, value).path(COOKIE_PATH).httpOnly(true).sameSite("Strict")
                .secure(!LOCAL_HOSTS.contains(request.getServerName()));
    }
}
