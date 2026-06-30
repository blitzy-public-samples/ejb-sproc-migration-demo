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
 * message converters and timeout settings.</p>
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
}
