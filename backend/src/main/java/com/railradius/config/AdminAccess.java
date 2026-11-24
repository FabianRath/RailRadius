package com.railradius.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Tells the admin from everyone else, by the shared secret {@code railradius.admin.token} (empty means nobody is
 * admin). It answers two questions: may this request call the admin API, and is this search exempt from the rate
 * limit. Wrong tokens count against the client under both, so neither is a way to guess the token.
 */
@Component
@Slf4j
public class AdminAccess {

    public static final String HEADER = "X-Admin-Token";

    /** The answer to an admin call, in the order it is checked. */
    public enum AdminCall { DISABLED, THROTTLED, WRONG_TOKEN, ALLOWED }

    private final byte[] tokenBytes;
    private final AdminSessions sessions;
    private final AdminLoginThrottle loginThrottle;

    public AdminAccess(@Value("${railradius.admin.token:}") String token, AdminSessions sessions,
                       AdminLoginThrottle loginThrottle) {
        this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        this.sessions = sessions;
        this.loginThrottle = loginThrottle;
        if (token.isEmpty()) log.warn("railradius.admin.token is not set: the admin API is disabled");
    }

    /**
     * Admin calls accept only the header: the session cookie would be sent along by a forged cross-site request. A
     * throttled client is turned away before its token is checked, so even a correct guess tells it nothing.
     */
    public AdminCall checkAdminCall(HttpServletRequest request) {
        if (!isConfigured()) return AdminCall.DISABLED;
        String clientIp = request.getRemoteAddr();
        if (loginThrottle.isBlocked(clientIp)) return AdminCall.THROTTLED;
        if (!matches(request.getHeader(HEADER))) {
            loginThrottle.recordFailure(clientIp);
            return AdminCall.WRONG_TOKEN;
        }
        return AdminCall.ALLOWED;
    }

    /**
     * True for the token in the header or a live admin session in the cookie, which is how the browser's
     * EventSource, unable to send headers, proves the admin.
     */
    public boolean exemptsSearch(HttpServletRequest request) {
        return sessions.isLive(request) || headerProvesAdmin(request);
    }

    // Unlike an admin call, a search without the header is no failed login: nearly every search is sent without one
    private boolean headerProvesAdmin(HttpServletRequest request) {
        String given = request.getHeader(HEADER);
        if (given == null || !isConfigured()) return false;
        String clientIp = request.getRemoteAddr();
        if (loginThrottle.isBlocked(clientIp)) return false;
        if (matches(given)) return true;
        loginThrottle.recordFailure(clientIp);
        return false;
    }

    private boolean isConfigured() {
        return tokenBytes.length > 0;
    }

    // Constant-time, so response timing reveals nothing about the token
    private boolean matches(String given) {
        return isConfigured() && given != null
                && MessageDigest.isEqual(tokenBytes, given.getBytes(StandardCharsets.UTF_8));
    }
}
