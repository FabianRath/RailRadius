package com.railradius.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Guards the admin API with the {@link AdminToken} in the {@value AdminToken#HEADER} header. Without a configured
 * token every admin call is refused, so a deployment that forgets to set one never exposes reloads or cache deletion.
 * Failed logins are throttled per client IP by {@link AdminLoginThrottle}; a blocked IP is refused before its token
 * is checked, so even a correct guess tells it nothing.
 */
@Component
@RequiredArgsConstructor
public class AdminTokenInterceptor implements HandlerInterceptor {

    private final AdminToken adminToken;
    private final AdminLoginThrottle loginThrottle;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!adminToken.isConfigured()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        String clientIp = request.getRemoteAddr();
        if (loginThrottle.isBlocked(clientIp)) {
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
            return false;
        }
        if (!adminToken.matches(request.getHeader(AdminToken.HEADER))) {
            loginThrottle.recordFailure(clientIp);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
