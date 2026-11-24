package com.railradius.config;

import com.railradius.config.AdminAccess.AdminCall;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAccessTest {

    private static final String GUESSER_IP = "203.0.113.7";
    private static final String OTHER_IP = "198.51.100.9";

    private final AdminSessions sessions = new AdminSessions();
    private final AdminAccess access = new AdminAccess("secret", sessions, new AdminLoginThrottle());

    @Test
    void tokenInTheHeaderOrALiveSessionInTheCookieExemptsASearch() {
        assertThat(access.exemptsSearch(requestWithToken("secret", GUESSER_IP))).isTrue();
        assertThat(access.exemptsSearch(requestWithCookie(liveSessionId()))).isTrue();
    }

    @Test
    void rawTokenAWrongOrNoValueInTheCookieDoesNotExemptASearch() {
        MockHttpServletRequest wrongCookie = new MockHttpServletRequest();
        wrongCookie.setCookies(new Cookie(AdminSessions.COOKIE, "guess"), new Cookie("other", "secret"));

        assertThat(access.exemptsSearch(requestWithCookie("secret"))).isFalse();
        assertThat(access.exemptsSearch(wrongCookie)).isFalse();
        assertThat(access.exemptsSearch(new MockHttpServletRequest())).isFalse();
    }

    @Test
    void unconfiguredTokenMakesNobodyAdmin() {
        MockHttpServletRequest request = requestWithToken("", GUESSER_IP);
        request.setCookies(new Cookie(AdminSessions.COOKIE, ""));
        AdminAccess unconfigured = new AdminAccess("", new AdminSessions(), new AdminLoginThrottle());

        assertThat(unconfigured.exemptsSearch(request)).isFalse();
        assertThat(unconfigured.checkAdminCall(request)).isEqualTo(AdminCall.DISABLED);
    }

    @Test
    void matchingTokenIsAllowedAWrongOrMissingOneIsNot() {
        assertThat(access.checkAdminCall(requestWithToken("secret", GUESSER_IP))).isEqualTo(AdminCall.ALLOWED);
        assertThat(access.checkAdminCall(requestWithToken("guess", GUESSER_IP))).isEqualTo(AdminCall.WRONG_TOKEN);
        assertThat(access.checkAdminCall(requestFrom(GUESSER_IP))).isEqualTo(AdminCall.WRONG_TOKEN);
    }

    @Test
    void adminCallIgnoresTheSessionCookie() {
        assertThat(access.checkAdminCall(requestWithCookie(liveSessionId()))).isEqualTo(AdminCall.WRONG_TOKEN);
    }

    @Test
    void sixthCallAfterFiveFailuresIsThrottledEvenWithTheRightToken() {
        for (int i = 0; i < 5; i++) access.checkAdminCall(requestWithToken("guess" + i, GUESSER_IP));

        assertThat(access.checkAdminCall(requestWithToken("secret", GUESSER_IP))).isEqualTo(AdminCall.THROTTLED);
    }

    @Test
    void missingTokensOnAdminCallsCountAsFailures() {
        for (int i = 0; i < 5; i++) access.checkAdminCall(requestFrom(GUESSER_IP));

        assertThat(access.checkAdminCall(requestWithToken("secret", GUESSER_IP))).isEqualTo(AdminCall.THROTTLED);
    }

    @Test
    void failuresFromOneIpLeaveAnotherIpUnaffected() {
        for (int i = 0; i < 6; i++) access.checkAdminCall(requestWithToken("guess", GUESSER_IP));

        assertThat(access.checkAdminCall(requestWithToken("secret", OTHER_IP))).isEqualTo(AdminCall.ALLOWED);
    }

    @Test
    void failuresSpreadAcrossOneIpv6Slash64ThrottleIt() {
        for (int i = 1; i <= 5; i++) access.checkAdminCall(requestWithToken("guess", "2001:db8:1:2::" + i));

        assertThat(access.checkAdminCall(requestWithToken("secret", "2001:db8:1:2::ff")))
                .isEqualTo(AdminCall.THROTTLED);
        assertThat(access.checkAdminCall(requestWithToken("secret", "2001:db8:1:3::1")))
                .isEqualTo(AdminCall.ALLOWED);
    }

    @Test
    void rightTokenUnderTheLimitIsAllowed() {
        for (int i = 0; i < 4; i++) access.checkAdminCall(requestWithToken("guess", GUESSER_IP));

        assertThat(access.checkAdminCall(requestWithToken("secret", GUESSER_IP))).isEqualTo(AdminCall.ALLOWED);
    }

    @Test
    void wrongTokenOnASearchCountsTowardsTheThrottle() {
        for (int i = 0; i < 5; i++) access.exemptsSearch(requestWithToken("guess" + i, GUESSER_IP));

        assertThat(access.checkAdminCall(requestWithToken("secret", GUESSER_IP))).isEqualTo(AdminCall.THROTTLED);
    }

    @Test
    void missingTokenOnASearchDoesNotCount() {
        for (int i = 0; i < 6; i++) access.exemptsSearch(requestFrom(GUESSER_IP));

        assertThat(access.checkAdminCall(requestWithToken("secret", GUESSER_IP))).isEqualTo(AdminCall.ALLOWED);
    }

    @Test
    void throttledClientsTokenNoLongerExemptsItsSearches() {
        for (int i = 0; i < 5; i++) access.checkAdminCall(requestWithToken("guess" + i, GUESSER_IP));

        assertThat(access.exemptsSearch(requestWithToken("secret", GUESSER_IP))).isFalse();
    }

    @Test
    void liveSessionStillExemptsASearchFromAThrottledClient() {
        for (int i = 0; i < 5; i++) access.checkAdminCall(requestWithToken("guess" + i, GUESSER_IP));
        MockHttpServletRequest request = requestWithCookie(liveSessionId());
        request.setRemoteAddr(GUESSER_IP);

        assertThat(access.exemptsSearch(request)).isTrue();
    }

    private String liveSessionId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("localhost");
        return sessions.start(request).getValue();
    }

    private static MockHttpServletRequest requestFrom(String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/gtfs-stats");
        request.setRemoteAddr(clientIp);
        return request;
    }

    private static MockHttpServletRequest requestWithToken(String token, String clientIp) {
        MockHttpServletRequest request = requestFrom(clientIp);
        request.addHeader(AdminAccess.HEADER, token);
        return request;
    }

    private static MockHttpServletRequest requestWithCookie(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AdminSessions.COOKIE, value));
        return request;
    }
}
