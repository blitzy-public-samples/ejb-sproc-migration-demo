package org.jboss.as.quickstarts.kitchensink.users.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-to-service authentication gate for the {@code /internal/**} endpoints of users-service
 * (review M13 — the internal endpoints must not be reachable by arbitrary callers).
 *
 * <p>The internal lifetime-spend write contract ({@code POST /internal/members/{id}/total-spend}) is
 * intended only for orders-service. This filter enforces a shared-secret check: the caller must
 * present an {@code X-Internal-Api-Key} header equal to the configured {@code internal.api-key}
 * (sourced from the {@code INTERNAL_API_KEY} environment variable; no secret is committed).</p>
 *
 * <p><b>Fail-closed:</b> if no server-side key is configured (blank {@code internal.api-key}) the
 * filter DENIES every {@code /internal/**} request with 401 rather than falling open — so a
 * misconfiguration locks the endpoint instead of exposing it. A present-but-mismatched (or missing)
 * header is likewise rejected with 401. The comparison is constant-time to avoid leaking the key
 * through response-timing differences.</p>
 *
 * <p>Only {@code /internal/**} is guarded (see {@link #shouldNotFilter(HttpServletRequest)}); all
 * externally consumed endpoints — {@code /api/members/**}, including {@code /api/members/{id}/tier}
 * used by the frontend/e2e — and the actuator health endpoint pass through untouched. Integration
 * tests exercise the spend service through the service layer (never over HTTP {@code /internal}), so
 * this filter is inert during tests where {@code internal.api-key} resolves to empty.</p>
 */
@Component
public class InternalApiKeyFilter extends OncePerRequestFilter {

    /** Header carrying the shared service-to-service secret. */
    static final String API_KEY_HEADER = "X-Internal-Api-Key";

    /** Path prefix (within the servlet context) that this filter protects. */
    private static final String INTERNAL_PREFIX = "/internal";

    private final String configuredKey;

    public InternalApiKeyFilter(@Value("${internal.api-key:}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    /**
     * Skip everything that is not under {@code /internal/**}. The path is computed by stripping the
     * servlet context-path (e.g. {@code /users}) from the request URI so the match is independent of
     * the deployed context-path.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !pathWithinApplication(request).startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Fail-closed: a blank server-side key means the internal channel is not configured, so deny.
        if (configuredKey == null || configuredKey.isBlank()) {
            deny(response);
            return;
        }
        String provided = request.getHeader(API_KEY_HEADER);
        if (provided == null || !constantTimeEquals(configuredKey, provided)) {
            deny(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Returns the request path with the servlet context-path removed (e.g.
     * {@code /users/internal/members/1/total-spend} -&gt; {@code /internal/members/1/total-spend}).
     */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /**
     * Writes a sanitized 401 response with no detail about why the request was rejected (never echoes
     * the expected key or whether one is configured — CWE-209 information-disclosure avoidance).
     */
    private static void deny(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
    }

    /**
     * Constant-time comparison of the configured and provided keys to avoid timing side-channels.
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
