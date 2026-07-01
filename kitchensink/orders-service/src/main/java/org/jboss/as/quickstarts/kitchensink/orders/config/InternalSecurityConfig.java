package org.jboss.as.quickstarts.kitchensink.orders.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the orders-service web-edge interceptors.
 *
 * <p>{@link InternalServiceAuthInterceptor} is bound on {@code /internal/**} (matched within the
 * {@code /orders} context-path, which Spring strips before pattern matching) — a service-token guard
 * for the internal spend-read endpoint ({@code GET /internal/members/{id}/spend}), callable only by
 * trusted peer services (users-service). That endpoint is NEW service-to-service plumbing introduced
 * by this migration (AAP §0.6.2 Contract 3), not part of the original monolith's observable surface,
 * so guarding it does not affect storefront continuity.</p>
 *
 * <p><b>The public cart/order surface ({@code /api/orders/**}: add-to-cart, remove-from-cart, preview,
 * submit, order lookup, order history) is intentionally left OPEN and unauthenticated.</b> The AAP
 * freezes the pre-existing PHP storefront as functionally unchanged (AAP §0.3.4) and mandates
 * preserving the monolith's observable behavior exactly (AAP §0.7.1 / §0.1.1). The legacy JAX-RS order
 * resource carried no authentication, and the demo storefront calls these endpoints anonymously
 * (client-side demo login, no token). Gating this surface would break that mandated continuity (the
 * entire commerce flow would return 401 to the frozen storefront), so no member-authentication
 * interceptor is registered here. The {@code /actuator/**} health/metrics endpoints likewise remain
 * open for orchestration probes.</p>
 */
@Configuration
public class InternalSecurityConfig implements WebMvcConfigurer {

    private final InternalServiceAuthInterceptor internalServiceAuthInterceptor;

    public InternalSecurityConfig(InternalServiceAuthInterceptor internalServiceAuthInterceptor) {
        this.internalServiceAuthInterceptor = internalServiceAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Service-token guard for the internal (service-to-service) spend-read endpoint only.
        // The public /api/orders/** cart/order surface is intentionally unauthenticated to preserve
        // the frozen PHP storefront's continuity (AAP §0.3.4, §0.7.1) — see class javadoc.
        registry.addInterceptor(internalServiceAuthInterceptor).addPathPatterns("/internal/**");
    }
}
