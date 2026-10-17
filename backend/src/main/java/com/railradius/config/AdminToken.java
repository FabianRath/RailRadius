package com.railradius.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The shared secret ({@code railradius.admin.token}) that unlocks the admin API and exempts searches from the rate
 * limit. Empty means nobody is admin.
 */
@Component
@Slf4j
public class AdminToken {

    public static final String HEADER = "X-Admin-Token";
    // The browser's EventSource cannot send headers, so its searches prove the admin with this cookie, which holds an
    // AdminSessions id, never the token. Admin calls accept only the header: a cookie would be sent along by a forged
    // cross-site request.
    public static final String COOKIE = "railradius_admin";
    // All of /api, so that logout (DELETE /api/admin/session) can name the session it ends
    private static final String COOKIE_PATH = "/api";

    // Dev runs on plain-HTTP localhost, where curl and some browsers (Safari) drop a Secure cookie
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final byte[] tokenBytes;
    private final AdminSessions sessions;

    public AdminToken(@Value("${railradius.admin.token:}") String token, AdminSessions sessions) {
        this.sessions = sessions;
        this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        if (token.isEmpty()) log.warn("railradius.admin.token is not set: the admin API is disabled");
    }

    public boolean isConfigured() {
        return tokenBytes.length > 0;
    }

    /** Constant-time, so response timing reveals nothing about the token. Always false without a configured token. */
    public boolean matches(String given) {
        return isConfigured() && given != null
                && MessageDigest.isEqual(tokenBytes, given.getBytes(StandardCharsets.UTF_8));
    }

    /** True if the request carries the token in the header or a live admin session id in the cookie. */
    public boolean sentWith(HttpServletRequest request) {
        return matches(request.getHeader(HEADER)) || sessions.isLive(sessionId(request).orElse(null));
    }

    /**
     * Starts an admin session and returns its cookie, sent to /api and lasting until the browser
     * closes. Secure except on localhost: {@code request.isSecure()} is false behind a TLS proxy.
     */
    public ResponseCookie startSession(HttpServletRequest request) {
        return cookieBuilder(sessions.create(), request).build();
    }

    /** Ends the session named in the request's cookie, if any, and returns the cookie that deletes it. */
    public ResponseCookie endSession(HttpServletRequest request) {
        sessions.invalidate(sessionId(request).orElse(null));
        return cookieBuilder("", request).maxAge(Duration.ZERO).build();
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
