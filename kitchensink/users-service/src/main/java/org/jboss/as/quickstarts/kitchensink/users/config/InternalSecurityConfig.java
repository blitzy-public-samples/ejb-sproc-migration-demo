package org.jboss.as.quickstarts.kitchensink.users.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link InternalServiceAuthInterceptor} for the users-service spend-mutation endpoint.
 *
 * <p>The interceptor is bound to the single-segment wildcard pattern
 * {@code /api/members/}&#42;{@code /spend} ONLY (matched within the {@code /users} context-path, which
 * Spring strips before pattern matching). This protects {@code POST /api/members/{id}/spend} while
 * leaving the public surface open: the Contract-2 tier read {@code GET /api/members/{id}/tier},
 * member creation {@code POST /api/members}, the member listing/lookup endpoints, and the actuator
 * endpoints are all unguarded.</p>
 */
@Configuration
public class InternalSecurityConfig implements WebMvcConfigurer {

    private final InternalServiceAuthInterceptor internalServiceAuthInterceptor;

    public InternalSecurityConfig(InternalServiceAuthInterceptor internalServiceAuthInterceptor) {
        this.internalServiceAuthInterceptor = internalServiceAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalServiceAuthInterceptor).addPathPatterns("/api/members/*/spend");
    }
}
