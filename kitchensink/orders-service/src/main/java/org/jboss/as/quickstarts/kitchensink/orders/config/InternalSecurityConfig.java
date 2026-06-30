package org.jboss.as.quickstarts.kitchensink.orders.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link InternalServiceAuthInterceptor} for the orders-service internal endpoints.
 *
 * <p>The interceptor is bound to {@code /internal/**} (matched within the {@code /orders}
 * context-path, which Spring strips before pattern matching). This protects
 * {@code GET /internal/members/{id}/spend} while leaving the public {@code /api/**} surface and
 * the {@code /actuator/**} health/metrics endpoints open.</p>
 */
@Configuration
public class InternalSecurityConfig implements WebMvcConfigurer {

    private final InternalServiceAuthInterceptor internalServiceAuthInterceptor;

    public InternalSecurityConfig(InternalServiceAuthInterceptor internalServiceAuthInterceptor) {
        this.internalServiceAuthInterceptor = internalServiceAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalServiceAuthInterceptor).addPathPatterns("/internal/**");
    }
}
