package com.railreach.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenInterceptorTest {

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void matchingTokenIsLetThrough() throws Exception {
        assertThat(new AdminTokenInterceptor("secret").preHandle(requestWithToken("secret"), response, null)).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void wrongTokenIsUnauthorized() throws Exception {
        assertThat(new AdminTokenInterceptor("secret").preHandle(requestWithToken("guess"), response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/gtfs-stats");
        assertThat(new AdminTokenInterceptor("secret").preHandle(request, response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void unconfiguredTokenRefusesEveryCallEvenAnEmptyHeader() throws Exception {
        assertThat(new AdminTokenInterceptor("").preHandle(requestWithToken(""), response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void corsPreflightPassesWithoutToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/admin/reload-gtfs");
        request.addHeader("Origin", "https://example.org");
        request.addHeader("Access-Control-Request-Method", "POST");
        assertThat(new AdminTokenInterceptor("secret").preHandle(request, response, null)).isTrue();
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/gtfs-stats");
        request.addHeader(AdminTokenInterceptor.HEADER, token);
        return request;
    }
}
