package com.railradius.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenInterceptorTest {

    private static final String GUESSER_IP = "203.0.113.7";
    private static final String OTHER_IP = "198.51.100.9";

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void matchingTokenIsLetThrough() throws Exception {
        assertThat(interceptor("secret").preHandle(requestWithToken("secret"), response, null)).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void wrongTokenIsUnauthorized() throws Exception {
        assertThat(interceptor("secret").preHandle(requestWithToken("guess"), response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/gtfs-stats");
        assertThat(interceptor("secret").preHandle(request, response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void unconfiguredTokenRefusesEveryCallEvenAnEmptyHeader() throws Exception {
        assertThat(interceptor("").preHandle(requestWithToken(""), response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void sixthCallAfterFiveFailuresIsTooManyRequestsEvenWithTheRightToken() throws Exception {
        AdminTokenInterceptor interceptor = interceptor("secret");
        for (int i = 0; i < 5; i++) {
            assertThat(statusOf(interceptor, requestWithToken("guess" + i, GUESSER_IP))).isEqualTo(401);
        }
        assertThat(statusOf(interceptor, requestWithToken("secret", GUESSER_IP))).isEqualTo(429);
    }

    @Test
    void missingTokensCountAsFailures() throws Exception {
        AdminTokenInterceptor interceptor = interceptor("secret");
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/gtfs-stats");
            request.setRemoteAddr(GUESSER_IP);
            assertThat(statusOf(interceptor, request)).isEqualTo(401);
        }
        assertThat(statusOf(interceptor, requestWithToken("secret", GUESSER_IP))).isEqualTo(429);
    }

    @Test
    void failuresFromOneIpLeaveAnotherIpUnaffected() throws Exception {
        AdminTokenInterceptor interceptor = interceptor("secret");
        for (int i = 0; i < 6; i++) statusOf(interceptor, requestWithToken("guess", GUESSER_IP));
        assertThat(statusOf(interceptor, requestWithToken("secret", OTHER_IP))).isEqualTo(200);
    }

    @Test
    void rightTokenUnderTheLimitIsLetThrough() throws Exception {
        AdminTokenInterceptor interceptor = interceptor("secret");
        for (int i = 0; i < 4; i++) statusOf(interceptor, requestWithToken("guess", GUESSER_IP));
        assertThat(statusOf(interceptor, requestWithToken("secret", GUESSER_IP))).isEqualTo(200);
    }

    private static int statusOf(AdminTokenInterceptor interceptor, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse callResponse = new MockHttpServletResponse();
        interceptor.preHandle(request, callResponse, null);
        return callResponse.getStatus();
    }

    private static AdminTokenInterceptor interceptor(String token) {
        return new AdminTokenInterceptor(new AdminToken(token, new AdminSessions()), new AdminLoginThrottle());
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        return requestWithToken(token, GUESSER_IP);
    }

    private static MockHttpServletRequest requestWithToken(String token, String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/gtfs-stats");
        request.addHeader(AdminToken.HEADER, token);
        request.setRemoteAddr(clientIp);
        return request;
    }
}
