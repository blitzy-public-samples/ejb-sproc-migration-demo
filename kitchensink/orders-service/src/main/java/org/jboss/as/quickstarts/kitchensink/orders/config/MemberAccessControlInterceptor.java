package org.jboss.as.quickstarts.kitchensink.orders.config;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Application-specific access control for the public {@code /api/orders/**} surface.
 *
 * <p>Before this interceptor, every cart and order endpoint accepted an arbitrary {@code {memberId}}
 * (or {@code {orderId}}) with no authentication or ownership enforcement, so any caller could read or
 * mutate another member's cart, submit orders on their behalf, and read their order history (CRITICAL
 * authorization finding). This interceptor closes that gap by resolving each request to a
 * {@link CallerIdentity} via {@link CallerAuthenticator} and applying:</p>
 *
 * <ul>
 *   <li><b>Member-scoped endpoints</b> — any path carrying a {@code {memberId}} template variable
 *       (add-to-cart, remove-from-cart, preview, submit, order history): the caller must be
 *       authenticated (else <b>401</b>) and must be permitted to act on that member (its own id, or a
 *       trusted SERVICE caller) (else <b>403</b>).</li>
 *   <li><b>Order lookup</b> — {@code GET /api/orders/{orderId}} carries no {@code {memberId}}; the
 *       owning member is unknown until the order is loaded. The caller must be authenticated here
 *       (else <b>401</b>); the resolved {@link CallerIdentity} is stored under
 *       {@value #CALLER_IDENTITY_ATTRIBUTE} so the controller can enforce ownership against the loaded
 *       order's {@code memberId} (returning 403 for a non-owner).</li>
 * </ul>
 *
 * <p>The interceptor only acts on controller invocations ({@link HandlerMethod}); CORS preflight and
 * other non-controller handlers pass through untouched. It is intentionally an MVC interceptor rather
 * than {@code spring-boot-starter-security} to honor the migration's minimal-change discipline,
 * matching the existing {@link InternalServiceAuthInterceptor} approach.</p>
 */
@Component
public class MemberAccessControlInterceptor implements HandlerInterceptor {

    /**
     * Request attribute under which the resolved {@link CallerIdentity} is published for the
     * order-lookup ({@code GET /api/orders/{orderId}}) ownership check performed by the controller.
     */
    public static final String CALLER_IDENTITY_ATTRIBUTE =
            "org.jboss.as.quickstarts.kitchensink.orders.CALLER_IDENTITY";

    /** URI template variable that identifies the member a request is scoped to. */
    private static final String MEMBER_ID_VAR = "memberId";

    private final CallerAuthenticator callerAuthenticator;

    public MemberAccessControlInterceptor(CallerAuthenticator callerAuthenticator) {
        this.callerAuthenticator = callerAuthenticator;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Only guard actual controller methods; let CORS preflight / resource handlers through.
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        CallerIdentity caller = callerAuthenticator.authenticate(request);
        // Publish the identity so the controller can finish the order-lookup ownership decision.
        request.setAttribute(CALLER_IDENTITY_ATTRIBUTE, caller);

        String memberIdVar = memberIdPathVariable(request);
        if (memberIdVar != null) {
            // Member-scoped endpoint: authentication + ownership required.
            if (!caller.isAuthenticated()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401
                return false;
            }
            Long targetMemberId = parseLong(memberIdVar);
            if (targetMemberId == null || !caller.canAccessMember(targetMemberId)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN); // 403
                return false;
            }
            return true;
        }

        // Order-lookup (GET /api/orders/{orderId}): authentication required now; ownership deferred to
        // the controller, which compares the loaded order's memberId against CALLER_IDENTITY_ATTRIBUTE.
        if (!caller.isAuthenticated()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401
            return false;
        }
        return true;
    }

    /** Returns the raw {@code {memberId}} URI template variable for this request, or {@code null}. */
    private static String memberIdPathVariable(HttpServletRequest request) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attr instanceof Map<?, ?> vars) {
            Object value = vars.get(MEMBER_ID_VAR);
            return value == null ? null : value.toString();
        }
        return null;
    }

    private static Long parseLong(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
