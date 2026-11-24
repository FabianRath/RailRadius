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

    // Images: data: for MapLibre's control icons in its CSS, blob: for the sprite and raster tiles it decodes.
    // MapLibre starts its worker from the same-origin worker file, so 'self' suffices for workers.
    private static final String CONTENT_SECURITY_POLICY = policy("script-src 'self'");
    // The browser engine's worker (#12) takes the CSP of its own script. TeaVM's runtime builds its JavaScript glue
    // with new Function and compiles WebAssembly, so only there may scripts eval; the page itself never may
    private static final String ENGINE_WORKER_PATH = "/js/engine-worker.js";
    private static final String ENGINE_WORKER_POLICY = policy("script-src 'self' 'unsafe-eval' 'wasm-unsafe-eval'");
    private static final String NOSNIFF = "nosniff";
    private static final String FRAME_OPTIONS = "DENY";
    // Same-origin calls need no referrer, and OpenFreeMap needs none, so no page URL leaves the site
    private static final String REFERRER_POLICY = "no-referrer";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", ENGINE_WORKER_PATH.equals(request.getRequestURI())
                ? ENGINE_WORKER_POLICY : CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", NOSNIFF);
        response.setHeader("X-Frame-Options", FRAME_OPTIONS);
        response.setHeader("Referrer-Policy", REFERRER_POLICY);
        chain.doFilter(request, response);
    }

    private static String policy(String scriptSources) {
        return String.join("; ",
                "default-src 'self'",
                scriptSources,
                "connect-src 'self' " + MAP_HOST,
                "img-src 'self' data: blob: " + MAP_HOST,
                "worker-src 'self'",
                "object-src 'none'",
                "base-uri 'self'",
                "form-action 'self'",
                "frame-ancestors 'none'");
    }
}
