package org.jboss.as.quickstarts.kitchensink.orders.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Service-to-service access control for orders-service's INTERNAL endpoints.
 *
 * <p>The internal spend-read endpoint ({@code GET /internal/members/{id}/spend}) discloses member
 * spend data and is intended to be callable only by trusted peer services (users-service). Without
 * enforcement, any caller with network access could read it (MAJOR security finding). This
 * interceptor enforces a shared service token: every request to a guarded path must present the
 * {@value #INTERNAL_TOKEN_HEADER} header whose value matches {@code ${security.internal.token}};
 * otherwise the request is rejected with HTTP 401 before reaching the controller.</p>
 *
 * <p>This is a deliberately lightweight mechanism (a shared bearer-style token) rather than the
 * full {@code spring-boot-starter-security} stack: the migration's binding rules require minimal,
 * isolated change and the existing integration suite must remain green. The token is externalized
 * (overridable via the {@code SECURITY_INTERNAL_TOKEN} environment variable) and never hard-coded
 * at a call site.</p>
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
            // Reject unauthenticated/invalid callers before any controller logic runs.
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
