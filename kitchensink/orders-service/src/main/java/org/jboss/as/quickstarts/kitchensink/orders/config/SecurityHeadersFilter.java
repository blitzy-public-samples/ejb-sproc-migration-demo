package org.jboss.as.quickstarts.kitchensink.orders.config;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Emits baseline HTTP security-hardening response headers on EVERY response served by
 * orders-service (REST API, actuator, and error dispatches alike).
 *
 * <p><b>Why this exists.</b> The migration deliberately does not pull in
 * {@code spring-boot-starter-security} (the binding rules require minimal, isolated change and the
 * access control that <em>is</em> required is implemented with lightweight Spring MVC interceptors).
 * A side effect is that Spring Security's automatic hardening headers are absent, so by default the
 * services return only {@code Content-Type}. This servlet filter restores the small, universally
 * safe subset of hardening headers appropriate for a headless JSON API, closing the QA finding that
 * common hardening headers were missing.</p>
 *
 * <p><b>Why a servlet {@link OncePerRequestFilter} rather than a {@code HandlerInterceptor}.</b> A
 * filter sits ahead of the {@code DispatcherServlet}, so it covers responses that never reach an
 * MVC handler — most importantly the actuator endpoints (e.g. {@code /actuator/health}) and Spring
 * Boot's {@code /error} dispatch. {@link #shouldNotFilterErrorDispatch()} is overridden to
 * {@code false} so the headers are re-applied on the ERROR dispatch even if error handling reset the
 * response buffer. Headers are written before {@link FilterChain#doFilter} so they are present on
 * the committed response regardless of the downstream outcome (200, 404, 5xx, ...).</p>
 *
 * <p><b>Header set (and deliberate omissions).</b></p>
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — stops browsers MIME-sniffing a response into an
 *       unintended, potentially executable content type.</li>
 *   <li>{@code X-Frame-Options: DENY} — anti-clickjacking; these JSON endpoints are never meant to
 *       be framed.</li>
 *   <li>{@code Referrer-Policy: no-referrer} — prevents leaking the request URL (which can embed
 *       resource ids) to third parties via the {@code Referer} header.</li>
 * </ul>
 * <p>{@code Cache-Control} is intentionally NOT forced here: cacheable reads should stay cacheable
 * and globally pinning {@code no-store} would be a behavioural change beyond this hardening fix.
 * {@code Content-Security-Policy}/{@code Permissions-Policy} are document/browser feature-context
 * headers with no meaningful effect on non-rendered JSON, so they are omitted to keep the change
 * minimal and free of per-consumer tuning. Each header is only set when absent, so a handler that
 * intentionally sets its own value is never clobbered.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    static final String X_FRAME_OPTIONS = "X-Frame-Options";
    static final String REFERRER_POLICY = "Referrer-Policy";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Set the hardening headers up front (before the chain runs and the response is committed)
        // so they are present on every outcome: successful API reads, actuator health, and error
        // responses. containsHeader guards avoid overriding a value a handler set on purpose.
        if (!response.containsHeader(X_CONTENT_TYPE_OPTIONS)) {
            response.setHeader(X_CONTENT_TYPE_OPTIONS, "nosniff");
        }
        if (!response.containsHeader(X_FRAME_OPTIONS)) {
            response.setHeader(X_FRAME_OPTIONS, "DENY");
        }
        if (!response.containsHeader(REFERRER_POLICY)) {
            response.setHeader(REFERRER_POLICY, "no-referrer");
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Also run on the ERROR dispatch so the hardening headers are guaranteed on error responses
     * (e.g. 404/5xx) even if the container reset the response buffer during error handling.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
