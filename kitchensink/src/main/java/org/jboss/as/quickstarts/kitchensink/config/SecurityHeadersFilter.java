package org.jboss.as.quickstarts.kitchensink.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds baseline HTTP security-hardening headers to every response served by the application.
 *
 * <p>The migrated service is a JSON REST API (the legacy JSF UI was retired); its responses were
 * previously emitted with only {@code Content-Type} and {@code Date}. This filter sets two widely
 * recommended, contract-safe response headers that QA flagged as missing:
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} &mdash; instructs browsers not to MIME-sniff a
 *       response away from its declared {@code Content-Type}.</li>
 *   <li>{@code X-Frame-Options: DENY} &mdash; the API serves JSON only and is never meant to be
 *       rendered inside a frame, so framing is denied outright (clickjacking defense).</li>
 * </ul>
 *
 * <p>Implementation notes (Spring Boot 3.x): this is a plain {@link OncePerRequestFilter} from
 * {@code spring-web} (already on the classpath via {@code spring-boot-starter-web}); no Spring Security
 * dependency is introduced and no existing endpoint contract changes — the headers are purely additive.
 * Annotating it {@code @Component} lets Spring Boot auto-detect and register it in the servlet filter
 * chain (it is discovered by the {@code @SpringBootApplication} component scan of the base package). The
 * headers are written before {@link FilterChain#doFilter} so they are present even once the response
 * body begins streaming, and a {@code containsHeader} guard avoids clobbering any value a downstream
 * component may have already set.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (!response.containsHeader("X-Content-Type-Options")) {
            response.setHeader("X-Content-Type-Options", "nosniff");
        }
        if (!response.containsHeader("X-Frame-Options")) {
            response.setHeader("X-Frame-Options", "DENY");
        }

        filterChain.doFilter(request, response);
    }
}
