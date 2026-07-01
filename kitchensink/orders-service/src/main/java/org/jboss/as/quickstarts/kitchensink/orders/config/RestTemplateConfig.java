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
package org.jboss.as.quickstarts.kitchensink.orders.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Spring configuration that exposes the single shared {@link RestTemplate} bean used by
 * orders-service's cross-service HTTP clients.
 *
 * <p>orders-service is an HTTP consumer: it calls marketplace-service (price/quote) and
 * users-service (member tier). The {@code client/} components ({@code MarketplaceClient} and
 * {@code UsersClient}) constructor-inject this exact bean. This is the ONLY HTTP mechanism in
 * the service; {@code @Service} classes must never use {@code RestTemplate} directly.</p>
 *
 * <p>Built via {@link RestTemplateBuilder} so it inherits Spring Boot's auto-configured
 * message converters. On top of that, this bean sets explicit <b>connect</b> and <b>read</b>
 * timeouts so a slow or hung peer (marketplace-service or users-service) can never block an
 * orders-service request thread indefinitely. Without a read timeout the default request
 * factory waits forever, which under a degraded peer exhausts the Tomcat worker pool and the
 * Hikari connection pool and cascades into orders-service unavailability. When a timeout fires
 * the {@link RestTemplate} raises a {@code ResourceAccessException}, which the thin
 * {@code client/} components ({@code MarketplaceClient}, {@code UsersClient}) translate into a
 * {@code ServiceUnavailableException} (HTTP 503) &mdash; the AAP's fail-fast resilience posture
 * rather than an unbounded wait.</p>
 *
 * <p>Both timeouts are externalized (AAP &sect;0.3.3 &mdash; externalized configuration) via
 * {@code services.http-client.connect-timeout-ms} / {@code services.http-client.read-timeout-ms}
 * in {@code application.properties}, with safe built-in defaults (2000&nbsp;ms connect,
 * 5000&nbsp;ms read) so the protection applies even if the properties are omitted.</p>
 */
@Configuration
public class RestTemplateConfig {

    /** Maximum time to establish the TCP connection to a downstream service before failing fast. */
    private final Duration connectTimeout;

    /** Maximum time to wait for a downstream response once connected before failing fast. */
    private final Duration readTimeout;

    public RestTemplateConfig(
            @Value("${services.http-client.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${services.http-client.read-timeout-ms:5000}") long readTimeoutMs) {
        this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // connectTimeout / readTimeout are the non-deprecated fluent RestTemplateBuilder methods in
        // Spring Boot 3.5; they bound every outbound cross-service call. A breach surfaces as a
        // ResourceAccessException that the client components map to ServiceUnavailableException (503).
        return builder
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .build();
    }
}
