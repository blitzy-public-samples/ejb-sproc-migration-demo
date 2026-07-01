package org.jboss.as.quickstarts.kitchensink.orders.security;

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
 * Service-to-service authentication gate for the {@code /internal/**} endpoints of orders-service
 * (review M13 — the internal endpoints must not be reachable by arbitrary callers).
 *
 * <p>The internal Contract 3 (Spend) read ({@code GET /internal/members/{id}/spend?days=}) is
 * intended ONLY for users-service (the nightly tier-recalculation fan-out, AAP &sect;0.6.2 /
 * &sect;0.6.5). Without a gate, any caller that can reach orders-service could harvest per-member
 * spend totals. This filter enforces a shared-secret check: the caller must present an
 * {@code X-Internal-Api-Key} header equal to the configured {@code internal.api-key} (sourced from the
 * {@code INTERNAL_API_KEY} environment variable; no secret is committed). users-service's
 * {@code OrdersClientImpl} attaches exactly this header on the spend read.</p>
 *
 * <p><b>Fail-closed:</b> if no server-side key is configured (blank {@code internal.api-key}) the
 * filter DENIES every {@code /internal/**} request with 401 rather than falling open — so a
 * misconfiguration locks the endpoint instead of exposing it. A present-but-mismatched (or missing)
 * header is likewise rejected with 401. The comparison is constant-time to avoid leaking the key
 * through response-timing differences.</p>
 *
 * <p>Only {@code /internal/**} is guarded (see {@link #shouldNotFilter(HttpServletRequest)}); all
 * externally consumed endpoints — {@code /api/orders/**}, used by the frontend/e2e — and the actuator
 * health endpoint pass through untouched. Integration tests exercise the order/spend logic through the
 * service layer (never over HTTP {@code /internal}), so this filter is inert during tests where
 * {@code internal.api-key} resolves to empty.</p>
 *
 * <p>This is a SEPARATE class from the users-service
 * {@code org.jboss.as.quickstarts.kitchensink.users.security.InternalApiKeyFilter} (intentional
 * duplication — no shared module, upholding the boundary rule AAP &sect;0.7.2).</p>
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
     * servlet context-path (e.g. {@code /orders}) from the request URI so the match is independent of
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
     * {@code /orders/internal/members/1/spend} -&gt; {@code /internal/members/1/spend}).
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
