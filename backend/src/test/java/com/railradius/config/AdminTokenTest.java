package com.railradius.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenTest {

    private final AdminToken adminToken = new AdminToken("secret", new AdminSessions());

    @Test
    void requestWithTheTokenInTheHeaderOrALiveSessionInTheCookieIsFromTheAdmin() {
        MockHttpServletRequest header = new MockHttpServletRequest();
        header.addHeader(AdminToken.HEADER, "secret");

        assertThat(adminToken.sentWith(header)).isTrue();
        assertThat(adminToken.sentWith(requestWithCookie(adminToken.startSession(localRequest()).getValue())))
                .isTrue();
    }

    @Test
    void requestWithTheRawTokenAWrongOrNoValueInTheCookieIsNotFromTheAdmin() {
        MockHttpServletRequest wrongCookie = new MockHttpServletRequest();
        wrongCookie.setCookies(new Cookie(AdminToken.COOKIE, "guess"), new Cookie("other", "secret"));

        assertThat(adminToken.sentWith(requestWithCookie("secret"))).isFalse();
        assertThat(adminToken.sentWith(wrongCookie)).isFalse();
        assertThat(adminToken.sentWith(new MockHttpServletRequest())).isFalse();
    }

    @Test
    void unconfiguredTokenMakesNobodyAdmin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AdminToken.HEADER, "");
        request.setCookies(new Cookie(AdminToken.COOKIE, ""));

        assertThat(new AdminToken("", new AdminSessions()).sentWith(request)).isFalse();
    }

    @Test
    void sessionCookieCarriesARandomSessionIdNotTheToken() {
        ResponseCookie first = adminToken.startSession(localRequest());
        ResponseCookie second = adminToken.startSession(localRequest());

        assertThat(first.getValue()).isNotEmpty().isNotEqualTo("secret").isNotEqualTo(second.getValue());
    }

    @Test
    void endingTheSessionStopsItsCookieExemptingSearches() {
        String sessionId = adminToken.startSession(localRequest()).getValue();

        ResponseCookie cleared = adminToken.endSession(requestWithCookie(sessionId));

        assertThat(adminToken.sentWith(requestWithCookie(sessionId))).isFalse();
        assertThat(cleared.toString()).contains("Max-Age=0", "Path=/api");
    }

    @Test
    void cookieIsHttpOnlyStrictAndOnlySentToTheSearchEndpoints() {
        String cookie = adminToken.startSession(localRequest()).toString();

        assertThat(cookie).contains("HttpOnly", "SameSite=Strict", "Path=/api");
    }

    @Test
    void cookieIsSecureOnAPublicHostEvenOverPlainHttpBehindAProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("railradius.example");
        request.setSecure(false);

        assertThat(adminToken.startSession(request).isSecure()).isTrue();
        assertThat(adminToken.endSession(request).isSecure()).isTrue();
    }

    @Test
    void cookieIsNotSecureOnLocalhost() {
        for (String host : new String[] {"localhost", "127.0.0.1", "[::1]"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setServerName(host);

            assertThat(adminToken.startSession(request).isSecure()).as(host).isFalse();
        }
    }

    private static MockHttpServletRequest localRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("localhost");
        return request;
    }

    private static MockHttpServletRequest requestWithCookie(String value) {
        MockHttpServletRequest request = localRequest();
        request.setCookies(new Cookie(AdminToken.COOKIE, value));
        return request;
    }
}
