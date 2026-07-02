/*
 * JBoss, Home of Professional Open Source
 * Copyright 2015, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.quickstarts.kitchensink.rest;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Narrowly-scoped CORS policy for the JAX-RS API.
 *
 * <p>This is the single authorized backend change enabling the cross-origin React Module
 * Federation remote ({@code member-mfe}) to call the existing {@code /rest/members} API.
 * It is annotated {@link Provider}, so JAX-RS provider auto-discovery registers it under the
 * application's {@code @ApplicationPath("/rest")} activator ({@code JaxRsActivator}); it therefore
 * applies ONLY to {@code /rest/*} and never affects the PHP storefront or any non-{@code /rest} path.</p>
 *
 * <p>The class implements BOTH {@link ContainerRequestFilter} (to short-circuit CORS preflight
 * {@code OPTIONS} requests, which the JAX-RS resources do not otherwise handle) AND
 * {@link ContainerResponseFilter} (to inject the {@code Access-Control-*} response headers on
 * EVERY {@code /rest} response &mdash; including the 400/404/409 error responses produced by
 * {@code MemberResourceRESTService} &mdash; so the cross-origin remote can read success and error
 * bodies alike).</p>
 *
 * <p>Security: the allowlist is EXPLICIT and never uses a wildcard ({@code *}). The incoming
 * {@code Origin} is echoed into {@code Access-Control-Allow-Origin} only on an exact allowlist
 * match. No credentials are used, so {@code Access-Control-Allow-Credentials} is intentionally
 * omitted.</p>
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class CorsFilter implements ContainerRequestFilter, ContainerResponseFilter {

    // --- CORS header names (string literals to avoid coupling to optional constants) ---
    private static final String ORIGIN = "Origin";
    private static final String AC_REQUEST_METHOD = "Access-Control-Request-Method";
    private static final String AC_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String AC_ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String AC_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    private static final String AC_MAX_AGE = "Access-Control-Max-Age";
    private static final String VARY = "Vary";

    // --- Allowed values, derived from MemberResourceRESTService (@GET/@POST + JSON) ---
    private static final String ALLOWED_METHODS = "GET, POST, OPTIONS";
    private static final String ALLOWED_HEADERS = "Content-Type, Accept";
    private static final String MAX_AGE_SECONDS = "3600";

    /**
     * Deploy-time production origin. Supply EITHER as a JVM system property, e.g.:
     * <pre>-Dmember.mfe.allowed.origin=https://members.example.com</pre>
     * OR as the environment variable {@code MEMBER_MFE_ALLOWED_ORIGIN}. When unset (e.g. in
     * development) only the local dev origins below are permitted.
     */
    static final String PROD_ORIGIN_PROPERTY = "member.mfe.allowed.origin";
    static final String PROD_ORIGIN_ENV = "MEMBER_MFE_ALLOWED_ORIGIN";

    private final Set<String> allowedOrigins;

    public CorsFilter() {
        this.allowedOrigins = buildAllowedOrigins();
    }

    private static Set<String> buildAllowedOrigins() {
        Set<String> origins = new HashSet<>();
        // Development origin: the member-mfe dev server / host harness (MFE_PORT, default 5001).
        origins.add("http://localhost:5001");
        origins.add("http://127.0.0.1:5001");
        // Production origin, injected at deploy time (system property takes precedence over env var).
        String prod = System.getProperty(PROD_ORIGIN_PROPERTY);
        if (prod == null || prod.trim().isEmpty()) {
            prod = System.getenv(PROD_ORIGIN_ENV);
        }
        if (prod != null && !prod.trim().isEmpty()) {
            origins.add(prod.trim());
        }
        return Collections.unmodifiableSet(origins);
    }

    private boolean isAllowedOrigin(String origin) {
        return origin != null && allowedOrigins.contains(origin);
    }

    /**
     * Request filter: short-circuits CORS preflight. A cross-origin {@code POST} with a JSON
     * content type triggers an {@code OPTIONS} preflight that the JAX-RS resources would answer
     * with 405; here we detect it (OPTIONS + {@code Origin} + {@code Access-Control-Request-Method})
     * and abort the chain with a 200 response bearing the CORS headers (only when allowlisted).
     */
    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        final String origin = requestContext.getHeaderString(ORIGIN);
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())
                && origin != null
                && requestContext.getHeaderString(AC_REQUEST_METHOD) != null) {
            final Response.ResponseBuilder builder = Response.ok();
            if (isAllowedOrigin(origin)) {
                builder.header(AC_ALLOW_ORIGIN, origin)
                        .header(VARY, ORIGIN)
                        .header(AC_ALLOW_METHODS, ALLOWED_METHODS)
                        .header(AC_ALLOW_HEADERS, ALLOWED_HEADERS)
                        .header(AC_MAX_AGE, MAX_AGE_SECONDS);
            }
            requestContext.abortWith(builder.build());
        }
    }

    /**
     * Response filter: runs on ALL {@code /rest} responses (200 success with empty body, plus the
     * 400 constraint field-map, 409 duplicate-email, 404 not-found, and generic-400 error bodies
     * from {@code MemberResourceRESTService}) so the cross-origin remote can read every response.
     * The {@code Access-Control-Allow-Origin} header is added only for an allowlisted origin; a
     * wildcard is never emitted.
     */
    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        final String origin = requestContext.getHeaderString(ORIGIN);
        if (isAllowedOrigin(origin)) {
            responseContext.getHeaders().putSingle(AC_ALLOW_ORIGIN, origin);
            responseContext.getHeaders().add(VARY, ORIGIN);
            responseContext.getHeaders().putSingle(AC_ALLOW_METHODS, ALLOWED_METHODS);
            responseContext.getHeaders().putSingle(AC_ALLOW_HEADERS, ALLOWED_HEADERS);
        }
    }
}
