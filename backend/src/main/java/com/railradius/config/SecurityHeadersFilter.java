package com.railradius.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds the browser security headers to every response, static files and API alike. HSTS is left to the TLS proxy.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String MAP_HOST = "https://tiles.openfreemap.org";

    // 'unsafe-eval': Turf 6 builds functions with `new Function` when it loads; without it the global stays empty.
    // Images: data: for MapLibre's control icons in its CSS, blob: for the sprite and raster tiles it decodes.
    // MapLibre starts its worker from the same-origin worker file, so 'self' suffices for workers.
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'unsafe-eval'",
            "connect-src 'self' " + MAP_HOST,
            "img-src 'self' data: blob: " + MAP_HOST,
            "worker-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");
    private static final String NOSNIFF = "nosniff";
    private static final String FRAME_OPTIONS = "DENY";
    // Same-origin calls need no referrer, and OpenFreeMap needs none, so no page URL leaves the site
    private static final String REFERRER_POLICY = "no-referrer";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", NOSNIFF);
        response.setHeader("X-Frame-Options", FRAME_OPTIONS);
        response.setHeader("Referrer-Policy", REFERRER_POLICY);
        chain.doFilter(request, response);
    }
}
