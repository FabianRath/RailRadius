package com.railreach.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards the admin API with a shared secret in the {@value #HEADER} header. Without a configured token every admin
 * call is refused, so a deployment that forgets to set one never exposes reloads or cache deletion.
 */
@Component
@Slf4j
public class AdminTokenInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Token";

    private final byte[] tokenBytes;

    public AdminTokenInterceptor(@Value("${railreach.admin.token:}") String token) {
        this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        if (token.isEmpty()) log.warn("railreach.admin.token is not set: the admin API is disabled");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // Preflights carry no headers to check; the real request that follows is checked
        if (CorsUtils.isPreFlightRequest(request)) return true;
        if (tokenBytes.length == 0) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        String given = request.getHeader(HEADER);
        // Constant-time comparison, so response timing reveals nothing about the token
        if (given == null || !MessageDigest.isEqual(tokenBytes, given.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
