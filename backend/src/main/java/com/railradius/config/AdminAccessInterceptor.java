package com.railradius.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Guards the admin API with {@link AdminAccess#checkAdminCall}. Without a configured token every admin call is
 * refused, so a deployment that forgets to set one never exposes reloads or cache deletion.
 */
@Component
@RequiredArgsConstructor
public class AdminAccessInterceptor implements HandlerInterceptor {

    private final AdminAccess adminAccess;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        switch (adminAccess.checkAdminCall(request)) {
            case ALLOWED -> {
                return true;
            }
            case DISABLED -> response.sendError(HttpServletResponse.SC_FORBIDDEN);
            case THROTTLED -> response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
            case WRONG_TOKEN -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
        return false;
    }
}
