package com.railradius.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAccessInterceptorTest {

    private static final String CLIENT_IP = "203.0.113.7";

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void allowedCallIsLetThrough() throws Exception {
        assertThat(interceptor("secret").preHandle(requestWithToken("secret"), response, null)).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void wrongTokenIsUnauthorized() throws Exception {
        assertThat(interceptor("secret").preHandle(requestWithToken("guess"), response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void unconfiguredTokenIsForbidden() throws Exception {
        assertThat(interceptor("").preHandle(requestWithToken(""), response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void throttledClientIsTooManyRequests() throws Exception {
        AdminAccessInterceptor interceptor = interceptor("secret");
        for (int i = 0; i < 5; i++) {
            interceptor.preHandle(requestWithToken("guess"), new MockHttpServletResponse(), null);
        }

        assertThat(interceptor.preHandle(requestWithToken("secret"), response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
    }

    private static AdminAccessInterceptor interceptor(String token) {
        return new AdminAccessInterceptor(new AdminAccess(token, new AdminSessions(), new AdminLoginThrottle()));
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/gtfs-stats");
        request.addHeader(AdminAccess.HEADER, token);
        request.setRemoteAddr(CLIENT_IP);
        return request;
    }
}
