package com.railreach.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Guards the admin API with the {@link AdminToken} in the {@value AdminToken#HEADER} header. Without a configured
 * token every admin call is refused, so a deployment that forgets to set one never exposes reloads or cache deletion.
 */
@Component
@RequiredArgsConstructor
public class AdminTokenInterceptor implements HandlerInterceptor {

    private final AdminToken adminToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // Preflights carry no headers to check; the real request that follows is checked
        if (CorsUtils.isPreFlightRequest(request)) return true;
        if (!adminToken.isConfigured()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        if (!adminToken.matches(request.getHeader(AdminToken.HEADER))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
