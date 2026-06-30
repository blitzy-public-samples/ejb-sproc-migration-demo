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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;

import org.jboss.as.quickstarts.kitchensink.orders.exception.EmptyCartException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.MemberNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.model.Order;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderDraftItemRepository;
import org.jboss.as.quickstarts.kitchensink.orders.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
 * Integration test for the orders-service {@link OrderService}, migrated 1:1 from the legacy
 * JBoss EE integration test of the same name in the original {@code kitchensink} monolith
 * (191 lines, JUnit 4 + container-managed deployment).
 *
 * <p><strong>What it proves.</strong> The Java {@code OrderService} faithfully reproduces the
 * observable behavior of the retired {@code process_order()} PL/pgSQL stored procedure
 * (original db stored-procedure script, L196-290) through the single shared
 * {@code orchestrateOrder()} path that both {@code previewOrder()} and {@code submitOrder()}
 * flow through (AAP &sect;0.6.4, eliminating the legacy dual-path hazard):</p>
 * <ul>
 *   <li>{@code addToCart()} persists an {@code order_draft_items} row;</li>
 *   <li>{@code previewOrder()} on a non-empty cart yields a non-zero subtotal and total;</li>
 *   <li>{@code submitOrder()} creates a {@code CONFIRMED} order for the right member;</li>
 *   <li>{@code submitOrder()} clears the member's draft cart; and</li>
 *   <li>{@code submitOrder()} triggers the GAP-3 post-commit member-spend increment.</li>
 * </ul>
 *
 * <p><strong>Hybrid strategy (AAP &sect;0.6.3 &mdash; this IT requires the Contract&nbsp;1
 * price/quote stub AND the Contract&nbsp;2 tier stub).</strong></p>
 * <ul>
 *   <li><em>Real</em> PostgreSQL via Testcontainers, loaded with {@code db/01_schema.sql} then
 *       {@code db/03_seed_data.sql} only. The retired stored-procedure script is deliberately
 *       <strong>never</strong> loaded &mdash; the migrated services no longer invoke the
 *       procedures (zero native queries, AAP &sect;0.6.7), so parity is asserted purely against
 *       the seed values.</li>
 *   <li><em>Mocked</em> cross-service HTTP: {@code orchestrateOrder()} resolves a marketplace
 *       quote per draft item (Contract&nbsp;1 &mdash; best vendor + unit price + product weight in
 *       one round-trip, GAP-1/GAP-2) and the member tier through {@code DiscountService} &rarr;
 *       {@code UsersClient.getMemberTier()} (Contract&nbsp;2); {@code submitOrder()} additionally
 *       issues the post-commit member-spend increment (GAP-3, Contract-3-adjacent). All three are
 *       stubbed with {@link MockRestServiceServer} bound to the <em>shared</em> {@link RestTemplate}
 *       bean &mdash; no marketplace-service or users-service instance needs to run.</li>
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
 * <p><strong>Intentionally NOT {@code @Transactional}.</strong> The class is non-transactional so
 * that {@code submitOrder()} truly commits: the persisted CONFIRMED order and emptied draft cart are
 * observed as committed state, and the post-commit spend increment (which runs outside the orders
 * transaction, after the calculation-phase HTTP) actually fires. Wrapping the tests in a rolled-back
 * transaction would break all three of those guarantees. Cart cleanup therefore relies on the
 * inherited {@code deleteAll(Iterable)} (itself {@code @Transactional} in {@code SimpleJpaRepository})
 * applied to the result of a {@code findByMemberId} read.</p>
 *
 * <p><strong>Cross-domain boundary (AAP &sect;0.7.2).</strong> Only owning-service production types
 * are referenced ({@code orders.service.OrderService} and its nested {@code OrderPreview},
 * {@code orders.model.Order}, {@code orders.repository.OrderDraftItemRepository}). No
 * {@code ...marketplace.*} or {@code ...users.*} class is imported; every cross-service interaction
 * is exercised purely through the stubbed shared {@link RestTemplate}.</p>
 */
@SpringBootTest
@Testcontainers
public class OrderServiceIT {

    /**
     * Member 2 (Robert Torres, SILVER in {@code db/03_seed_data.sql}). Chosen to avoid conflicting
     * with the other orders-service integration tests; preserved exactly from the legacy test.
     */
    private static final Long TEST_MEMBER_ID = 2L;

    /** Destination ZIP preserved exactly from the legacy test; maps to seeded shipping zone 2. */
    private static final String TEST_ZIP = "27601";

    /**
     * Stubbed Contract&nbsp;1 marketplace quote body. {@code vendorId=1} exists in the seed (so the
     * persisted {@code order_items.vendor_id} FK is satisfied), {@code unitPrice=9.1692} is the
     * product-1 parity value, and {@code weightLbs=0.55} feeds the shipping-weight accumulation.
     * Deserializes into the client's local {@code ProductQuoteDto(vendorId, unitPrice, weightLbs)}.
     */
    private static final String QUOTE_JSON = "{\"vendorId\":1,\"unitPrice\":9.1692,\"weightLbs\":0.55}";

    /** Stubbed Contract&nbsp;2 tier body: member 2 (Robert Torres) is SILVER in the seed. */
    private static final String TIER_JSON = "{\"tier\":\"SILVER\"}";

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
     * the test profile). The {@code services.*.base-url} properties keep their
     * {@code application.properties} defaults &mdash; they are never reached because
     * {@link MockRestServiceServer} intercepts every outbound call on the shared {@link RestTemplate}.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /**
     * Loads the authoritative schema and seed data into the freshly started container over a direct
     * JDBC connection, in the mandatory order {@code 01_schema.sql} then {@code 03_seed_data.sql}.
     * Runs after the Testcontainers extension has started {@link #postgres} and before Spring's
     * context/DI is initialized, so {@code ddl-auto=validate} sees the tables it must validate.
     *
     * <p>The retired stored-procedure script is never referenced: the migrated service computes
     * the order in Java, so parity is asserted only against the seed values.</p>
     */
    @BeforeAll
    static void loadSchemaAndSeed() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/01_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/03_seed_data.sql"));
        }
    }

    /** The class under test &mdash; the orders-service orchestration logic extracted from {@code process_order}. */
    @Autowired
    private OrderService orderService;

    /**
     * The single shared {@link RestTemplate} bean from {@code RestTemplateConfig}. The same instance
     * is constructor-injected into {@code MarketplaceClient} and {@code UsersClient}, so binding
     * {@link MockRestServiceServer} to it below intercepts the quote, tier, and spend HTTP calls made
     * during {@code previewOrder()} / {@code submitOrder()}.
     */
    @Autowired
    private RestTemplate restTemplate;

    /** Used for cart cleanup and draft-row counts (replaces the legacy persistence-context JPQL). */
    @Autowired
    private OrderDraftItemRepository orderDraftItemRepository;

    /** Rebuilt fresh before each test so per-test expectations do not leak across methods. */
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        // Clear any leftover draft rows for the test member, then bind a fresh mock server to the
        // shared RestTemplate. ignoreExpectOrder(true) removes any assumption about the order in
        // which expectations match (findByMemberId iteration order is not deterministic).
        clearCart();
        mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
    }

    @AfterEach
    void tearDown() {
        clearCart();
    }

    /**
     * Clears the test member's draft cart. Uses the inherited {@code deleteAll(Iterable)} (which is
     * {@code @Transactional} in {@code SimpleJpaRepository}) applied to the result of the
     * {@code findByMemberId} read &mdash; this avoids the "no active transaction" hazard a bare
     * derived {@code deleteByMemberId} would hit in a non-transactional test class.
     */
    private void clearCart() {
        orderDraftItemRepository.deleteAll(orderDraftItemRepository.findByMemberId(TEST_MEMBER_ID));
    }

    /**
     * Stubs Contract&nbsp;1 (marketplace quote):
     * {@code GET {marketplace.base-url}/api/products/{id}/quote?qty=} &rarr; {@code 200 QUOTE_JSON}.
     * {@link ExpectedCount#manyTimes()} keeps the single expectation robust regardless of how many
     * draft items a test enqueues (the quote is fetched once per draft item). The matcher uses
     * {@code containsString("/quote")} so it matches the fully expanded request URI without coupling
     * to the configured base URL; the {@code /quote}, {@code /tier}, and {@code /spend} URIs are
     * disjoint, so there is no cross-matching between the three stubs.
     */
    private void expectQuote() {
        mockServer.expect(ExpectedCount.manyTimes(), requestTo(containsString("/quote")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(QUOTE_JSON, MediaType.APPLICATION_JSON));
    }

    /**
     * Stubs Contract&nbsp;2 (member tier):
     * {@code GET {users.base-url}/api/members/2/tier} &rarr; {@code 200 {"tier":"SILVER"}}.
     * Invoked once per orchestration, inside {@code DiscountService.calculateDiscount()}.
     */
    private void expectTier() {
        mockServer.expect(ExpectedCount.manyTimes(),
                requestTo(containsString("/api/members/" + TEST_MEMBER_ID + "/tier")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(TIER_JSON, MediaType.APPLICATION_JSON));
    }

    /**
     * Stubs the GAP-3 post-commit member-spend increment:
     * {@code POST {users.base-url}/api/members/2/spend} &rarr; {@code 200} (empty body, Void return).
     * The expected match count is parameterized so callers can require it exactly {@code once()}
     * (Test&nbsp;5, where {@code mockServer.verify()} asserts the increment fired) or allow
     * {@code manyTimes()} (Tests&nbsp;3-4, which assert other observable effects of submit).
     */
    private void expectSpend(ExpectedCount count) {
        mockServer.expect(count, requestTo(containsString("/api/members/" + TEST_MEMBER_ID + "/spend")))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess());
    }

    /**
     * Test 1: {@code addToCart()} should persist a new {@code order_draft_items} row.
     * No stubs &mdash; {@code addToCart()} performs no cross-service HTTP.
     */
    @Test
    public void testAddToCartInsertsRow() {
        orderService.addToCart(TEST_MEMBER_ID, 1L, 5);

        long count = orderDraftItemRepository.findByMemberId(TEST_MEMBER_ID).size();

        Assertions.assertTrue(count >= 1, "Cart should have at least 1 item after addToCart()");
    }

    /**
     * Test 2: {@code previewOrder()} on a non-empty cart should return a non-zero total.
     * Preview is non-transactional and performs the marketplace-quote and tier HTTP, but never the
     * (submit-only) spend increment.
     */
    @Test
    public void testPreviewOrderReturnsNonZeroTotal() {
        expectQuote();
        expectTier();
        orderService.addToCart(TEST_MEMBER_ID, 1L, 5);
        orderService.addToCart(TEST_MEMBER_ID, 3L, 10);

        OrderService.OrderPreview preview = orderService.previewOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        Assertions.assertNotNull(preview, "Preview should not be null");
        Assertions.assertTrue(preview.getSubtotal().compareTo(BigDecimal.ZERO) > 0, "Subtotal should be > 0");
        Assertions.assertTrue(preview.getTotal().compareTo(BigDecimal.ZERO) > 0, "Total should be > 0");
        Assertions.assertFalse(preview.getItems().isEmpty(), "Items list should not be empty");
    }

    /**
     * Test 3: {@code submitOrder()} should create a {@code CONFIRMED} order record for the member.
     * The order is read back through {@code OrderService.getOrder(orderId)} (replacing the legacy
     * {@code em.find(Order.class, ...)}). The spend increment is allowed via {@code manyTimes()}.
     */
    @Test
    public void testSubmitOrderCreatesConfirmedOrder() {
        expectQuote();
        expectTier();
        expectSpend(ExpectedCount.manyTimes());
        orderService.addToCart(TEST_MEMBER_ID, 2L, 3);

        Long orderId = orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);
        Assertions.assertNotNull(orderId, "Order ID should not be null after submitOrder()");

        Order order = orderService.getOrder(orderId);
        Assertions.assertNotNull(order, "Order entity should exist in DB");
        Assertions.assertEquals("CONFIRMED", order.getStatus(), "Order status should be CONFIRMED");
        Assertions.assertEquals(TEST_MEMBER_ID, order.getMemberId(), "Order member ID should match");
    }

    /**
     * Test 4: {@code submitOrder()} should clear the member's draft cart (committed state, observed
     * via a fresh {@code findByMemberId} read).
     */
    @Test
    public void testSubmitOrderClearsDraftCart() {
        expectQuote();
        expectTier();
        expectSpend(ExpectedCount.manyTimes());
        orderService.addToCart(TEST_MEMBER_ID, 1L, 2);
        orderService.addToCart(TEST_MEMBER_ID, 4L, 1);

        orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        long remaining = orderDraftItemRepository.findByMemberId(TEST_MEMBER_ID).size();
        Assertions.assertEquals(0L, remaining, "Draft cart should be empty after submitOrder()");
    }

    /**
     * Test 5: {@code submitOrder()} should increase the member's {@code total_spend}.
     *
     * <p><strong>GAP-3 re-expression (AAP &sect;0.6.6).</strong> The {@code member} table is owned by
     * users-service and is intentionally NOT mapped in orders-service, so the legacy
     * {@code em.find(Member.class, ...).getTotalSpend()} before/after read is impossible here.
     * Instead we assert the observable orders-service side effect: after the order commits,
     * orders-service issues the post-commit {@code POST .../api/members/2/spend} increment via
     * {@code UsersClient.incrementMemberSpend(memberId, total)} (replacing the legacy in-process
     * {@code UPDATE member SET total_spend += total}). Verifying that this POST occurred exactly once
     * &mdash; together with the quote and tier calls &mdash; proves {@code total_spend} would increase.</p>
     */
    @Test
    public void testMemberTotalSpendIncreasesAfterOrder() {
        expectQuote();
        expectTier();
        // The spend increment must occur exactly once (post-commit); mockServer.verify() asserts it.
        expectSpend(ExpectedCount.once());

        orderService.addToCart(TEST_MEMBER_ID, 5L, 2);
        orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        // Asserts quote (>=1), tier (>=1), and spend (exactly once) all occurred -> total_spend would increase.
        mockServer.verify();
    }

    /**
     * Test 6 (parity): {@code previewOrder()} must reproduce the {@code process_order} numbers
     * EXACTLY against the seed values, not merely "non-zero" (closing the review's exact-parity gap).
     *
     * <p>Single line: product&nbsp;1, qty&nbsp;5, stubbed unit price 9.1692, weight 0.55/unit; member&nbsp;2
     * is SILVER; destination ZIP 27601 maps to seeded zone&nbsp;2 (Southeast, base rate 0.9500).</p>
     * <ul>
     *   <li>subtotal = ROUND(9.1692 &times; 5, 2) = ROUND(45.846) = <b>45.85</b></li>
     *   <li>discount = ROUND(45.85 &times; 0.05 [SILVER], 2) = ROUND(2.2925) = <b>2.29</b></li>
     *   <li>shipping = MAX(5.99, 0.9500 &times; (0.55 &times; 5 = 2.75) = 2.6125) = <b>5.99</b> (no expedite)</li>
     *   <li>total = 45.85 &minus; 2.29 + 5.99 = <b>49.55</b></li>
     * </ul>
     * Assertions use {@link BigDecimal#compareTo} so they are scale-insensitive (49.55 == 49.5500).
     */
    @Test
    public void testPreviewOrderExactParity() {
        expectQuote();
        expectTier();
        orderService.addToCart(TEST_MEMBER_ID, 1L, 5);

        OrderService.OrderPreview preview = orderService.previewOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        Assertions.assertEquals(0, preview.getSubtotal().compareTo(new BigDecimal("45.85")),
                "subtotal parity: ROUND(9.1692 x 5) -> 45.85, got " + preview.getSubtotal());
        Assertions.assertEquals(0, preview.getDiscountAmount().compareTo(new BigDecimal("2.29")),
                "discount parity: SILVER 5% of 45.85 -> 2.29, got " + preview.getDiscountAmount());
        Assertions.assertEquals(0, preview.getShippingCost().compareTo(new BigDecimal("5.99")),
                "shipping parity: MAX(5.99, 0.95 x 2.75) -> 5.99, got " + preview.getShippingCost());
        Assertions.assertEquals(0, preview.getTotal().compareTo(new BigDecimal("49.55")),
                "total parity: 45.85 - 2.29 + 5.99 -> 49.55, got " + preview.getTotal());
    }

    /**
     * Test 7 (negative guard): an EMPTY cart must be rejected (process_order guard SQL L224-232,
     * ERRCODE P0004) BEFORE any line/discount/shipping/persist work. Guard&nbsp;1 (member-exists)
     * runs first and succeeds (the tier resolves), then Guard&nbsp;2 throws {@link EmptyCartException}
     * (HTTP 400). This prevents {@code submitOrder()} from ever persisting a confirmed zero-line order
     * and erroneously firing a spend increment.
     */
    @Test
    public void testPreviewOrderRejectsEmptyCart() {
        expectTier();
        // Cart is empty (cleared in setUp); deliberately add nothing.
        Assertions.assertThrows(EmptyCartException.class,
                () -> orderService.previewOrder(TEST_MEMBER_ID, TEST_ZIP, false),
                "An empty cart must be rejected with EmptyCartException before any calculation/persist");
    }

    /**
     * Test 8 (negative guard): a member ABSENT from users-service must be rejected (process_order
     * guard SQL L217-221, ERRCODE P0003). The Contract-2 tier probe (Guard&nbsp;1) returns 404, which
     * {@code UsersClient} maps to {@link MemberNotFoundException} (HTTP 404) -- and because the
     * member check precedes the cart check, it fires even with a non-empty cart and BEFORE any
     * marketplace quote is requested.
     */
    @Test
    public void testOrchestrateOrderRejectsMissingMember() {
        mockServer.expect(ExpectedCount.manyTimes(),
                requestTo(containsString("/api/members/" + TEST_MEMBER_ID + "/tier")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        // Non-empty cart proves the member guard fires FIRST (before the empty-cart and quote steps).
        orderService.addToCart(TEST_MEMBER_ID, 1L, 5);

        Assertions.assertThrows(MemberNotFoundException.class,
                () -> orderService.previewOrder(TEST_MEMBER_ID, TEST_ZIP, false),
                "A member absent from users-service must surface as MemberNotFoundException (404)");
    }

    /**
     * Test 9 (GAP-3 idempotency contract): {@code submitOrder()} must transmit BOTH the persisted
     * {@code orderId} idempotency key AND the exact order total on the post-commit spend increment.
     *
     * <p>The orderId is the globally-unique orders PK that lets users-service apply each order's spend
     * at most once (the durable correctness mechanism, AAP &sect;0.6.6); the amount is the exact parity
     * total (49.55, see {@link #testPreviewOrderExactParity()}). This asserts the request body shape
     * {@code {"orderId":<number>,"amount":49.55}} -- the orders-side half of the idempotency design.
     * (The users-side proof that a repeated orderId is applied only once lives in users-service's
     * {@code MemberSpendIT}.)</p>
     */
    @Test
    public void testSubmitOrderSendsOrderIdAndAmountForIdempotency() {
        expectQuote();
        expectTier();
        mockServer.expect(ExpectedCount.once(),
                requestTo(containsString("/api/members/" + TEST_MEMBER_ID + "/spend")))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.orderId").exists())
            .andExpect(jsonPath("$.orderId").isNumber())
            .andExpect(jsonPath("$.amount").value(49.55))
            .andRespond(withSuccess());

        orderService.addToCart(TEST_MEMBER_ID, 1L, 5);
        Long orderId = orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        Assertions.assertNotNull(orderId, "submitOrder() should return the persisted order id");
        // Confirms the spend POST fired exactly once carrying the orderId key + the 49.55 total.
        mockServer.verify();
    }
}
