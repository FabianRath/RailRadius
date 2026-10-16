package com.railradius.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenTest {

    private final AdminToken adminToken = new AdminToken("secret");

    @Test
    void requestWithTheTokenInTheHeaderOrTheCookieIsFromTheAdmin() {
        MockHttpServletRequest header = new MockHttpServletRequest();
        header.addHeader(AdminToken.HEADER, "secret");
        MockHttpServletRequest cookie = new MockHttpServletRequest();
        cookie.setCookies(new Cookie(AdminToken.COOKIE, "secret"));

        assertThat(adminToken.sentWith(header)).isTrue();
        assertThat(adminToken.sentWith(cookie)).isTrue();
    }

    @Test
    void requestWithAWrongOrNoTokenIsNotFromTheAdmin() {
        MockHttpServletRequest wrongCookie = new MockHttpServletRequest();
        wrongCookie.setCookies(new Cookie(AdminToken.COOKIE, "guess"), new Cookie("other", "secret"));

        assertThat(adminToken.sentWith(wrongCookie)).isFalse();
        assertThat(adminToken.sentWith(new MockHttpServletRequest())).isFalse();
    }

    @Test
    void unconfiguredTokenMakesNobodyAdmin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AdminToken.HEADER, "");
        request.setCookies(new Cookie(AdminToken.COOKIE, ""));

        assertThat(new AdminToken("").sentWith(request)).isFalse();
    }

    @Test
    void cookieIsHttpOnlyStrictAndOnlySentToTheSearchEndpoints() {
        String cookie = adminToken.cookie(true).toString();

        assertThat(cookie).contains("HttpOnly", "SameSite=Strict", "Secure", "Path=/api/reachability");
        assertThat(adminToken.clearedCookie().toString()).contains("Max-Age=0", "Path=/api/reachability");
    }
}
