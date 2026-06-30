package org.jboss.as.quickstarts.kitchensink.users.config;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Application-specific access control for the member read surface of {@code /api/members}.
 *
 * <p>Before this interceptor, the member listing, single-member lookup, and tier read were fully
 * public, exposing member PII (name, email, phone, tier, spend) to any unauthenticated caller
 * (CRITICAL authorization / PII finding). This interceptor closes that gap by resolving each request
 * to a {@link CallerIdentity} via {@link CallerAuthenticator} and applying, for <b>GET</b> requests
 * only:</p>
 *
 * <ul>
 *   <li><b>{@code GET /api/members}</b> (list all members) — discloses every member's PII, so it is an
 *       admin/service operation: only a trusted SERVICE caller is allowed (<b>401</b> if anonymous,
 *       <b>403</b> if an authenticated individual member).</li>
 *   <li><b>{@code GET /api/members/{id}}</b> and <b>{@code GET /api/members/{id}/tier}</b> — member
 *       lookup / tier read: the caller must be authenticated (<b>401</b>) and may read only its own
 *       record, while a trusted SERVICE caller may read any member (<b>403</b> otherwise).</li>
 * </ul>
 *
 * <p>Non-GET requests pass through untouched so the intentionally public, rate-limited registration
 * ({@code POST /api/members}) and the separately token-guarded spend mutation
 * ({@code POST /api/members/{id}/spend}) keep their own handling. The interceptor only acts on
 * controller invocations ({@link HandlerMethod}); CORS preflight and other handlers pass through. It
 * is intentionally an MVC interceptor rather than {@code spring-boot-starter-security} to honor the
 * migration's minimal-change discipline, matching the existing {@link InternalServiceAuthInterceptor}
 * approach.</p>
 */
@Component
public class MemberAccessControlInterceptor implements HandlerInterceptor {

    /** URI template variable that identifies the member a lookup/tier request is scoped to. */
    private static final String ID_VAR = "id";

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
        // Guard only the member READ surface (GET). POST registration (public, rate-limited) and the
        // token-guarded POST spend endpoint are handled by their own interceptors.
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        CallerIdentity caller = callerAuthenticator.authenticate(request);
        String idVar = idPathVariable(request);

        if (idVar == null) {
            // GET /api/members - listing all members' PII is a SERVICE/admin-only operation.
            if (!caller.isAuthenticated()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401
                return false;
            }
            if (!caller.isService()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN); // 403
                return false;
            }
            return true;
        }

        // GET /api/members/{id} or /api/members/{id}/tier - authentication + member ownership.
        if (!caller.isAuthenticated()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401
            return false;
        }
        Long targetMemberId = parseLong(idVar);
        if (targetMemberId == null || !caller.canAccessMember(targetMemberId)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN); // 403
            return false;
        }
        return true;
    }

    /** Returns the raw {@code {id}} URI template variable for this request, or {@code null}. */
    private static String idPathVariable(HttpServletRequest request) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attr instanceof Map<?, ?> vars) {
            Object value = vars.get(ID_VAR);
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
