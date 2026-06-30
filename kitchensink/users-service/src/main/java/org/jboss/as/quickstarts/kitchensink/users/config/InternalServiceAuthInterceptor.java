package org.jboss.as.quickstarts.kitchensink.users.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Service-to-service access control for the users-service spend-mutation endpoint.
 *
 * <p>{@code POST /api/members/{id}/spend} arbitrarily changes a member's {@code total_spend}; left
 * unprotected, any caller with network access could tamper with it (CRITICAL security finding). This
 * interceptor enforces a shared service token: a guarded request must present the
 * {@value #INTERNAL_TOKEN_HEADER} header matching {@code ${security.internal.token}}, otherwise it is
 * rejected with HTTP 401 before reaching the controller.</p>
 *
 * <p>Only the spend-mutation path is guarded (see {@code InternalSecurityConfig}); the public
 * Contract-2 tier read ({@code GET /api/members/{id}/tier}), member creation, and member queries
 * remain open. A deliberately lightweight shared-token mechanism (not the full
 * {@code spring-boot-starter-security} stack) is used to satisfy the migration's minimal-change rule
 * and keep the existing integration suite green; the token is externalized via the
 * {@code SECURITY_INTERNAL_TOKEN} environment variable.</p>
 */
@Component
public class InternalServiceAuthInterceptor implements HandlerInterceptor {

    /** Shared header carrying the service-to-service token. */
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final String expectedToken;

    public InternalServiceAuthInterceptor(@Value("${security.internal.token}") String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String provided = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (!tokenMatches(provided)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }

    /**
     * Constant-time token comparison (via {@link MessageDigest#isEqual}) to avoid leaking the
     * expected token through response-timing differences.
     */
    private boolean tokenMatches(String provided) {
        if (provided == null || expectedToken == null) {
            return false;
        }
        byte[] a = provided.getBytes(StandardCharsets.UTF_8);
        byte[] b = expectedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
