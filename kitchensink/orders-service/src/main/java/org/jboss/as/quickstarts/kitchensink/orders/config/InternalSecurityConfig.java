package org.jboss.as.quickstarts.kitchensink.orders.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the orders-service web-edge access-control interceptors.
 *
 * <p>Two complementary interceptors are bound (both matched within the {@code /orders} context-path,
 * which Spring strips before pattern matching):</p>
 *
 * <ul>
 *   <li>{@link InternalServiceAuthInterceptor} on {@code /internal/**} — a service-token guard for the
 *       internal spend-read endpoint ({@code GET /internal/members/{id}/spend}), callable only by
 *       trusted peer services.</li>
 *   <li>{@link MemberAccessControlInterceptor} on {@code /api/orders/**} — application-specific
 *       authentication and member-ownership enforcement for the public cart/order surface
 *       (add-to-cart, remove-from-cart, preview, submit, order lookup, order history). This closes the
 *       CRITICAL authorization gap where any caller could read or mutate another member's data.</li>
 * </ul>
 *
 * <p>The {@code /actuator/**} health/metrics endpoints remain open for orchestration probes.</p>
 */
@Configuration
public class InternalSecurityConfig implements WebMvcConfigurer {

    private final InternalServiceAuthInterceptor internalServiceAuthInterceptor;
    private final MemberAccessControlInterceptor memberAccessControlInterceptor;

    public InternalSecurityConfig(InternalServiceAuthInterceptor internalServiceAuthInterceptor,
                                  MemberAccessControlInterceptor memberAccessControlInterceptor) {
        this.internalServiceAuthInterceptor = internalServiceAuthInterceptor;
        this.memberAccessControlInterceptor = memberAccessControlInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Service-token guard for internal (service-to-service) endpoints.
        registry.addInterceptor(internalServiceAuthInterceptor).addPathPatterns("/internal/**");
        // Authentication + member-ownership guard for the public cart/order surface.
        registry.addInterceptor(memberAccessControlInterceptor).addPathPatterns("/api/orders/**");
    }
}
