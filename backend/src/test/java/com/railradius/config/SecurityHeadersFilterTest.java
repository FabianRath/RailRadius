package com.railradius.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {

    @Test
    void staticFileAndApiResponsesBothGetEverySecurityHeader() throws Exception {
        for (String path : new String[] {"/", "/js/main.js", "/api/health"}) {
            MockHttpServletResponse response = filter(path);

            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(response.getHeader("Content-Security-Policy")).isNotBlank();
        }
    }

    @Test
    void cspAllowsOnlyOwnOriginPlusOpenFreeMapAndForbidsFraming() throws Exception {
        String csp = filter("/").getHeader("Content-Security-Policy");

        assertThat(csp.split("; ")).contains(
                "default-src 'self'",
                "connect-src 'self' https://tiles.openfreemap.org",
                "img-src 'self' data: blob: https://tiles.openfreemap.org",
                "worker-src 'self'",
                "object-src 'none'",
                "frame-ancestors 'none'");
        assertThat(csp).doesNotContain("'unsafe-inline'");
    }

    @Test
    void requestStillReachesTheRestOfTheChain() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse(),
                chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletResponse filter(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", path), response,
                new MockFilterChain());
        return response;
    }
}
