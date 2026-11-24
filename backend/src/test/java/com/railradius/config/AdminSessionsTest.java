package com.railradius.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSessionsTest {

    private final AdminSessions sessions = new AdminSessions();

    @Test
    void startedSessionIsLiveUntilEnded() {
        String id = sessions.start(localRequest()).getValue();

        assertThat(sessions.isLive(requestWithCookie(id))).isTrue();
        ResponseCookie cleared = sessions.end(requestWithCookie(id));
        assertThat(sessions.isLive(requestWithCookie(id))).isFalse();
        assertThat(cleared.toString()).contains("Max-Age=0", "Path=/api");
    }

    @Test
    void unknownOrMissingCookieIsNotLive() {
        sessions.start(localRequest());

        assertThat(sessions.isLive(requestWithCookie("guess"))).isFalse();
        assertThat(sessions.isLive(localRequest())).isFalse();
    }

    @Test
    void cookieCarriesARandomUrlSafeUnpaddedId() {
        String first = sessions.start(localRequest()).getValue();

        assertThat(first).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(sessions.start(localRequest()).getValue());
    }

    @Test
    void cookieIsHttpOnlyStrictAndSentToAllOfApi() {
        String cookie = sessions.start(localRequest()).toString();

        assertThat(cookie).contains("HttpOnly", "SameSite=Strict", "Path=/api");
    }

    @Test
    void cookieIsSecureOnAPublicHostEvenOverPlainHttpBehindAProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("railradius.example");
        request.setSecure(false);

        assertThat(sessions.start(request).isSecure()).isTrue();
        assertThat(sessions.end(request).isSecure()).isTrue();
    }

    @Test
    void cookieIsNotSecureOnLocalhost() {
        for (String host : new String[] {"localhost", "127.0.0.1", "[::1]"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setServerName(host);

            assertThat(sessions.start(request).isSecure()).as(host).isFalse();
        }
    }

    private static MockHttpServletRequest localRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("localhost");
        return request;
    }

    private static MockHttpServletRequest requestWithCookie(String value) {
        MockHttpServletRequest request = localRequest();
        request.setCookies(new Cookie(AdminSessions.COOKIE, value));
        return request;
    }
}
