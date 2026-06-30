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
package org.jboss.as.quickstarts.kitchensink.orders;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;

import org.jboss.as.quickstarts.kitchensink.orders.repository.DiscountAuditRepository;
import org.jboss.as.quickstarts.kitchensink.orders.service.DiscountService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the orders-service {@link DiscountService}, migrated 1:1 from the legacy
 * JBoss EE integration test of the same name in the original monolith.
 *
 * <p><strong>What it proves.</strong> The Java {@code DiscountService} faithfully reproduces the
 * observable behavior of the retired {@code apply_customer_discount()} PL/pgSQL stored procedure
 * (defined in the original db stored-procedure script, L111-146):</p>
 * <ul>
 *   <li>the correct tier-based percentage is applied (BRONZE 2% / SILVER 5% / GOLD 8% /
 *       PLATINUM 12%), and</li>
 *   <li>a {@code discount_audit} row is persisted on <em>every</em> {@code calculateDiscount()}
 *       call (the preserved side effect).</li>
 * </ul>
 *
 * <p><strong>Hybrid strategy (AAP &sect;0.6.3 &mdash; this IT requires the Contract&nbsp;2 tier stub
 * only).</strong></p>
 * <ul>
 *   <li><em>Real</em> PostgreSQL via Testcontainers, loaded with {@code db/01_schema.sql} then
 *       {@code db/03_seed_data.sql} only. The retired stored-procedure script is deliberately
 *       <strong>never</strong> loaded &mdash; the migrated services no longer invoke the
 *       procedures (zero native queries, AAP &sect;0.6.7), so parity is asserted purely against
 *       the seed values.</li>
 *   <li><em>Mocked</em> tier HTTP call: {@code DiscountService} resolves the member tier through
 *       {@code UsersClient.getMemberTier(memberId)} (Contract&nbsp;2). The cross-domain
 *       {@code member} table is owned by users-service, so this orders-service test stubs that one
 *       downstream call with {@link MockRestServiceServer} bound to the <em>shared</em>
 *       {@link RestTemplate} bean &mdash; no users-service instance needs to run.</li>
 * </ul>
 *
 * <p><strong>Package placement is load-bearing.</strong> This test sits directly in the
 * orders-service base package {@code org.jboss.as.quickstarts.kitchensink.orders} (the legacy
 * {@code .test} segment is intentionally collapsed). That lets {@link SpringBootTest} auto-discover
 * the {@code @SpringBootApplication} class {@code OrdersApplication} by walking up the package tree,
 * so no explicit {@code classes = ...} attribute is required.</p>
 *
 * <p><strong>Bootstrap ordering.</strong> The static {@code @Container} is started by the
 * Testcontainers JUnit&nbsp;5 extension before anything else; the static {@link BeforeAll} hook then
 * loads schema + seed over a direct JDBC connection <em>before</em> the Spring context refreshes.
 * This matters because the module's {@code application.properties} sets
 * {@code spring.jpa.hibernate.ddl-auto=validate}: the five owned tables ({@code orders},
 * {@code order_items}, {@code order_draft_items}, {@code shipping_zones}, {@code discount_audit})
 * must already exist when Hibernate validates them at context startup.</p>
 *
 * <p>The class is intentionally non-transactional (no transaction is wrapped around the test
 * methods): every {@code calculateDiscount()} call must commit its {@code discount_audit} insert
 * so the repository row count reflects the persisted side effect (mirroring the original
 * procedure's autonomous behavior).</p>
 */
@SpringBootTest
@Testcontainers
public class DiscountServiceIT {

    /**
     * Disposable PostgreSQL backing the integration test. Declared {@code static} so the single
     * container is shared across all test methods (started once by the Testcontainers extension,
     * before {@link #loadSchemaAndSeed()} and before the Spring context boots).
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Points Spring's datasource at the running container. These dynamic values take precedence
     * over the {@code spring.datasource.*} entries in {@code application.properties} (whose
     * {@code ${DB_USERNAME}}/{@code ${DB_PASSWORD}} placeholders are therefore never evaluated in
     * the test profile).
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // security.internal.token has no default in application.properties (CWE-798 fix). UsersClient
        // injects it (and sends it on the guarded tier read invoked by DiscountService), so an
        // explicit test value is required for the context to start. The MockRestServiceServer tier
        // stub matches on URI + method only (no header assertion), so the token header is transparent.
        registry.add("security.internal.token", () -> "test-internal-token");
    }

    /**
     * Loads the authoritative schema and seed data into the freshly started container over a direct
     * JDBC connection, in the mandatory order {@code 01_schema.sql} then {@code 03_seed_data.sql}.
     * Runs after the Testcontainers extension has started {@link #postgres} and before Spring's
     * context/DI is initialized, so {@code ddl-auto=validate} sees the tables it must validate.
     *
     * <p>The retired stored-procedure script is never referenced: the migrated service computes
     * the discount in Java, so parity is asserted only against the seed values.</p>
     */
    @BeforeAll
    static void loadSchemaAndSeed() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/01_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/03_seed_data.sql"));
        }
    }

    /** The class under test &mdash; the orders-service domain logic extracted from the procedure. */
    @Autowired
    private DiscountService discountService;

    /**
     * The single shared {@link RestTemplate} bean from {@code RestTemplateConfig}. The same instance
     * is constructor-injected into {@code UsersClient}, so binding {@link MockRestServiceServer} to
     * it below intercepts the tier HTTP call made during {@code calculateDiscount()}.
     */
    @Autowired
    private RestTemplate restTemplate;

    /** Used to assert the {@code discount_audit} row count (replaces the legacy JPQL COUNT query). */
    @Autowired
    private DiscountAuditRepository discountAuditRepository;

    /** Rebuilt fresh before each test so per-test expectations do not leak across methods. */
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUpMockServer() {
        // Bind to the shared RestTemplate so UsersClient's outbound tier call is intercepted.
        // ignoreExpectOrder(true) removes any assumption about the order in which expectations match.
        mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
    }

    /**
     * Stubs Contract&nbsp;2 (member tier) for a single member:
     * {@code GET {users.base-url}/api/members/{memberId}/tier} &rarr; {@code 200 {"tier":"..."}}.
     *
     * <p>{@link ExpectedCount#manyTimes()} keeps the single expectation robust regardless of how
     * many times the service happens to call the endpoint (each test triggers exactly one
     * {@code calculateDiscount()} &rarr; one tier lookup). The matcher uses
     * {@code containsString} so it matches the fully expanded request URI
     * (e.g. {@code .../api/members/3/tier}) without coupling to the configured base URL.</p>
     *
     * @param memberId the member whose tier endpoint is stubbed
     * @param tier     the tier value to return (BRONZE / SILVER / GOLD / PLATINUM)
     */
    private void stubTier(Long memberId, String tier) {
        mockServer.expect(ExpectedCount.manyTimes(),
                requestTo(containsString("/api/members/" + memberId + "/tier")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"tier\":\"" + tier + "\"}", MediaType.APPLICATION_JSON));
    }

    /**
     * BRONZE member (seed member 3 = Emily Chen) discount on a $100 base must be ~2%
     * (BRONZE rate = 2%, preserved exactly from {@code apply_customer_discount}).
     */
    @Test
    public void testBronzeMemberDiscountIsApproximatelyTwoPercent() {
        stubTier(3L, "BRONZE");
        BigDecimal discount = discountService.calculateDiscount(3L, new BigDecimal("100.00"));
        Assertions.assertNotNull(discount, "Discount should not be null");
        Assertions.assertTrue(
            discount.compareTo(new BigDecimal("1.99")) >= 0
                && discount.compareTo(new BigDecimal("2.01")) <= 0,
            "BRONZE 2% discount on $100 should be between $1.99 and $2.01");
    }

    /**
     * GOLD member (seed member 1 = Jane Smith) discount on a $100 base must be ~8%
     * (GOLD rate = 8%, preserved exactly from {@code apply_customer_discount}).
     */
    @Test
    public void testGoldMemberDiscountIsApproximatelyEightPercent() {
        stubTier(1L, "GOLD");
        BigDecimal discount = discountService.calculateDiscount(1L, new BigDecimal("100.00"));
        Assertions.assertNotNull(discount, "Discount should not be null");
        Assertions.assertTrue(
            discount.compareTo(new BigDecimal("7.99")) >= 0
                && discount.compareTo(new BigDecimal("8.01")) <= 0,
            "GOLD 8% discount on $100 should be between $7.99 and $8.01");
    }

    /**
     * Every {@code calculateDiscount()} call must persist exactly one {@code discount_audit} row &mdash;
     * the preserved side effect of {@code apply_customer_discount} (AAP &sect;0.6.1, &sect;0.7.1).
     * Uses seed member 2 (Robert Torres, SILVER); the audit assertion is tier-independent.
     */
    @Test
    public void testDiscountAuditRowCreated() {
        stubTier(2L, "SILVER");
        long beforeCount = discountAuditRepository.count();
        discountService.calculateDiscount(2L, new BigDecimal("50.00"));
        long afterCount = discountAuditRepository.count();
        Assertions.assertEquals(beforeCount + 1, afterCount,
            "discount_audit should have exactly one more row after calculateDiscount()");
    }
}
