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
package org.jboss.as.quickstarts.kitchensink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot application entry point for the {@code kitchensink} module.
 *
 * <p>This class is the linchpin of the migration from Red Hat JBoss EAP 8 /
 * Jakarta EE 10 (a WAR deployed to an application server, which had no main
 * class) to a standalone, executable Spring Boot 3.x application launched with
 * {@code java -jar} or {@code mvn spring-boot:run}. The {@code spring-boot-maven-plugin}
 * repackages the build around this {@code main} method to produce that executable JAR.</p>
 *
 * <p>It is declared at the root of the base package
 * {@code org.jboss.as.quickstarts.kitchensink} (retained per the Minimal Change
 * Clause) by design: {@link SpringBootApplication @SpringBootApplication} drives
 * component scanning and entity scanning starting from this class's package, so
 * every sibling subpackage — {@code data} (Spring Data repositories),
 * {@code service} ({@code @Service} beans), {@code rest} ({@code @RestController}
 * beans), and {@code model} ({@code @Entity} classes) — is auto-discovered with
 * no additional {@code @ComponentScan}, {@code @EntityScan}, or
 * {@code @EnableJpaRepositories} configuration. Auto-configuration also wires the
 * PostgreSQL datasource and JPA settings from {@code application.properties}.</p>
 *
 * <p>{@link EnableScheduling @EnableScheduling} is required so that Spring
 * processes the {@code @Scheduled(cron = "0 0 2 * * *")} nightly tier
 * recalculation in {@code TierRecalculationService}; without it that scheduled
 * job would silently never fire. It is the Spring replacement for the former
 * EJB {@code @Singleton @Startup @Schedule} timer.</p>
 */
@SpringBootApplication
@EnableScheduling
public class KitchensinkApplication {

    /**
     * Boots the Spring application context and starts the embedded web server.
     *
     * @param args standard command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(KitchensinkApplication.class, args);
    }
}
