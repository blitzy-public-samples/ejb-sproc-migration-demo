package org.jboss.as.quickstarts.kitchensink.orders.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds baseline HTTP security response headers to every response served by orders-service
 * (QA checkpoint F7, Issue #1 — MINOR: "Missing HTTP security headers on all API responses").
 *
 * <p><b>What it sets</b> (on <i>every</i> response, before the request is dispatched):</p>
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — instructs browsers/intermediaries not to
 *       MIME-sniff a response away from its declared {@code Content-Type} (CWE-693 Protection
 *       Mechanism Failure). These endpoints serve {@code application/json}; nosniff prevents a
 *       body from ever being reinterpreted as HTML/script.</li>
 *   <li>{@code X-Frame-Options: DENY} — forbids rendering any response inside a frame/iframe
 *       (clickjacking defense). The finding accepts {@code X-Frame-Options: DENY} <i>or</i> a
 *       {@code Content-Security-Policy}; DENY is chosen as the simplest, most restrictive control
 *       for a pure JSON API that is never framed.</li>
 *   <li>{@code Cache-Control: no-store} — prohibits any shared/browser cache from storing the
 *       response (CWE-525). Applied uniformly so order payloads and the {@code /internal} spend
 *       response are never retained by an intermediary or browser cache.</li>
 * </ul>
 *
 * <p><b>Why HSTS is intentionally NOT set.</b> The finding's expected outcome scopes HSTS to
 * "where TLS is terminated at the app". These services run over plain HTTP behind a
 * TLS-terminating ingress in production; per RFC 6797 a {@code Strict-Transport-Security} header
 * received over a non-HTTPS connection is ignored by browsers, and emitting it here would be
 * misleading. HSTS therefore remains an ingress/TLS-terminator responsibility and is deliberately
 * omitted (consistent with the QA report's mitigating context).</p>
 *
 * <p><b>Ordering.</b> Registered at {@link Ordered#HIGHEST_PRECEDENCE} so the headers are written
 * before any other filter or handler can commit the response — including this service's
 * {@code InternalApiKeyFilter}. As a result the headers are present on successful {@code 2xx}
 * responses, on validation {@code 400}s, on the centralized {@code OrdersRestExceptionHandler}
 * {@code 4xx}/{@code 5xx} responses, and even on the {@code InternalApiKeyFilter}'s
 * {@code 401 Unauthorized} deny. The headers are set on the {@link HttpServletResponse} prior to
 * invoking the chain, so they persist regardless of the downstream outcome.</p>
 *
 * <p><b>Minimal-change / boundary rule (AAP &sect;0.7.2).</b> This is a small, additive
 * {@code OncePerRequestFilter} — no {@code spring-boot-starter-security} dependency is introduced,
 * preserving the intentional no-auth model of the public {@code /api/**} surface (the only
 * authentication in the system remains the {@code /internal/**} shared-secret gate). The class is
 * duplicated per service (each domain owns its own copy) rather than shared through a common
 * module, exactly as the existing {@code InternalApiKeyFilter} is duplicated, so the hard
 * cross-domain boundary is upheld: this class imports only {@code jakarta.servlet} and Spring
 * Framework types and nothing from another service package.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /** Prevents browsers from MIME-sniffing the response body away from its declared type. */
    static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    /** Clickjacking control: refuse to be rendered in a frame/iframe. */
    static final String FRAME_OPTIONS = "X-Frame-Options";

    /** Forbids caching of responses (protects order / spend payloads from intermediaries). */
    static final String CACHE_CONTROL = "Cache-Control";

    /**
     * Writes the security headers onto the response, then continues the filter chain. Setting the
     * headers up-front (rather than after {@code doFilter}) ensures they are present even when a
     * downstream filter or handler short-circuits or produces an error response.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader(CONTENT_TYPE_OPTIONS, "nosniff");
        response.setHeader(FRAME_OPTIONS, "DENY");
        response.setHeader(CACHE_CONTROL, "no-store");
        filterChain.doFilter(request, response);
    }
}
