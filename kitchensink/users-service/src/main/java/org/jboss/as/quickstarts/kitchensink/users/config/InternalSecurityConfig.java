package org.jboss.as.quickstarts.kitchensink.users.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the users-service web-edge access-control interceptors (all matched within the
 * {@code /users} context-path, which Spring strips before pattern matching).
 *
 * <ul>
 *   <li>{@link InternalServiceAuthInterceptor} on {@code /api/members/}&#42;{@code /spend} — the
 *       service-token guard for the spend-mutation endpoint ({@code POST /api/members/{id}/spend}),
 *       callable only by trusted peer services. Unchanged by the authorization fix.</li>
 *   <li>{@link MemberAccessControlInterceptor} on {@code /api/members} and {@code /api/members/}&#42;&#42;
 *       (excluding the spend path) — authentication and ownership/admin enforcement for the member
 *       READ surface: full-member listing (SERVICE-only), single-member lookup and tier read
 *       (owner or SERVICE). This closes the CRITICAL PII-exposure gap.</li>
 *   <li>{@link RegistrationRateLimitInterceptor} on {@code /api/members} — per-IP abuse control for
 *       the intentionally public registration endpoint ({@code POST /api/members}).</li>
 * </ul>
 *
 * <p>The {@code /actuator/**} endpoints remain open for orchestration probes, and member registration
 * stays UNAUTHENTICATED (200 OK) per the AAP member-create parity rule — protected by rate limiting
 * rather than authentication.</p>
 */
@Configuration
public class InternalSecurityConfig implements WebMvcConfigurer {

    private final InternalServiceAuthInterceptor internalServiceAuthInterceptor;
    private final MemberAccessControlInterceptor memberAccessControlInterceptor;
    private final RegistrationRateLimitInterceptor registrationRateLimitInterceptor;

    public InternalSecurityConfig(InternalServiceAuthInterceptor internalServiceAuthInterceptor,
                                  MemberAccessControlInterceptor memberAccessControlInterceptor,
                                  RegistrationRateLimitInterceptor registrationRateLimitInterceptor) {
        this.internalServiceAuthInterceptor = internalServiceAuthInterceptor;
        this.memberAccessControlInterceptor = memberAccessControlInterceptor;
        this.registrationRateLimitInterceptor = registrationRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Service-token guard for the spend mutation (unchanged).
        registry.addInterceptor(internalServiceAuthInterceptor).addPathPatterns("/api/members/*/spend");
        // Authentication + ownership/admin guard for the member READ surface (GET-only internally).
        // Excludes the spend path so only the dedicated service-token guard applies there.
        registry.addInterceptor(memberAccessControlInterceptor)
                .addPathPatterns("/api/members", "/api/members/**")
                .excludePathPatterns("/api/members/*/spend");
        // Per-IP abuse control for the public registration POST (POST-only internally).
        registry.addInterceptor(registrationRateLimitInterceptor).addPathPatterns("/api/members");
    }
}
