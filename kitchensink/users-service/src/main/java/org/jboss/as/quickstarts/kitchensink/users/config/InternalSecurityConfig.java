package org.jboss.as.quickstarts.kitchensink.users.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the users-service web-edge interceptors (all matched within the {@code /users}
 * context-path, which Spring strips before pattern matching).
 *
 * <ul>
 *   <li>{@link InternalServiceAuthInterceptor} on {@code /api/members/}&#42;{@code /spend} — the
 *       service-token guard for the internal spend-mutation endpoint
 *       ({@code POST /api/members/{id}/spend}), callable only by trusted peer services
 *       (orders-service, GAP-3). This is NEW service-to-service plumbing introduced by this migration,
 *       not part of the original monolith surface, so guarding it does not affect storefront
 *       continuity.</li>
 *   <li>{@link RegistrationRateLimitInterceptor} on {@code /api/members} — per-IP abuse control for
 *       the intentionally public registration endpoint ({@code POST /api/members}); registration stays
 *       unauthenticated (200-OK parity) and is protected by rate limiting rather than authentication.</li>
 * </ul>
 *
 * <p><b>The member READ surface ({@code GET /api/members}, {@code GET /api/members/{id}},
 * {@code GET /api/members/{id}/tier}) is intentionally left OPEN and unauthenticated.</b> The AAP
 * freezes the pre-existing PHP storefront as functionally unchanged (AAP §0.3.4) and mandates
 * preserving the monolith's observable behavior exactly (AAP §0.7.1 / §0.1.1). The legacy JAX-RS member
 * resource carried no authentication. Gating the read surface would break that mandated continuity
 * (the scope-listed {@code GET /api/members} continuity check, and the peer tier read used by
 * orders-service), so no member-authentication interceptor is registered. The {@code /actuator/**}
 * endpoints remain open for orchestration probes.</p>
 */
@Configuration
public class InternalSecurityConfig implements WebMvcConfigurer {

    private final InternalServiceAuthInterceptor internalServiceAuthInterceptor;
    private final RegistrationRateLimitInterceptor registrationRateLimitInterceptor;

    public InternalSecurityConfig(InternalServiceAuthInterceptor internalServiceAuthInterceptor,
                                  RegistrationRateLimitInterceptor registrationRateLimitInterceptor) {
        this.internalServiceAuthInterceptor = internalServiceAuthInterceptor;
        this.registrationRateLimitInterceptor = registrationRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Service-token guard for the internal (service-to-service) spend-mutation endpoint only.
        registry.addInterceptor(internalServiceAuthInterceptor).addPathPatterns("/api/members/*/spend");
        // Per-IP abuse control for the intentionally public registration POST. The member READ surface
        // is intentionally unauthenticated to preserve the frozen PHP storefront's continuity
        // (AAP §0.3.4, §0.7.1) — see class javadoc.
        registry.addInterceptor(registrationRateLimitInterceptor).addPathPatterns("/api/members");
    }
}
