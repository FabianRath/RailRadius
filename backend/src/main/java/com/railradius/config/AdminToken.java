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

/**
 * The shared secret ({@code railradius.admin.token}) that unlocks the admin API and exempts searches from the rate
 * limit. Empty means nobody is admin.
 */
@Component
@Slf4j
public class AdminToken {

    public static final String HEADER = "X-Admin-Token";
    // The browser's EventSource cannot send headers, so its searches prove the admin with this cookie. Admin calls
    // accept only the header: a cookie would be sent along by a forged cross-site request.
    public static final String COOKIE = "railradius_admin";
    private static final String COOKIE_PATH = "/api/reachability";

    private final String token;
    private final byte[] tokenBytes;

    public AdminToken(@Value("${railradius.admin.token:}") String token) {
        this.token = token;
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

    /** True if the request carries the token in the header or in the admin cookie. */
    public boolean sentWith(HttpServletRequest request) {
        if (matches(request.getHeader(HEADER))) return true;
        Cookie[] cookies = request.getCookies();
        return cookies != null && Arrays.stream(cookies)
                .anyMatch(cookie -> COOKIE.equals(cookie.getName()) && matches(cookie.getValue()));
    }

    /** The admin cookie, sent only to the search endpoints and lasting until the browser closes. */
    public ResponseCookie cookie(boolean secure) {
        return cookieBuilder(token).secure(secure).build();
    }

    public ResponseCookie clearedCookie() {
        return cookieBuilder("").maxAge(Duration.ZERO).build();
    }

    private static ResponseCookie.ResponseCookieBuilder cookieBuilder(String value) {
        return ResponseCookie.from(COOKIE, value).path(COOKIE_PATH).httpOnly(true).sameSite("Strict");
    }
}
