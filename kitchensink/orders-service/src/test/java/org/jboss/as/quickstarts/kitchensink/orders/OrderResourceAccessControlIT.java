package org.jboss.as.quickstarts.kitchensink.orders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.jboss.as.quickstarts.kitchensink.orders.config.CallerAuthenticator;
import org.jboss.as.quickstarts.kitchensink.orders.config.InternalServiceAuthInterceptor;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderDraftItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Web-edge authorization integration test for the public {@code /api/orders/**} surface, driven
 * through {@link MockMvc} so the real Spring MVC chain -- including
 * {@link org.jboss.as.quickstarts.kitchensink.orders.config.MemberAccessControlInterceptor} and the
 * controller's order-ownership check -- is exercised end to end.
 *
 * <p>This test was added to resolve the CRITICAL authorization finding on
 * {@code OrderResourceRESTService}: before the fix, cart mutations, order submission, order lookup,
 * and member order history accepted an arbitrary {@code {memberId}}/{@code {orderId}} with no
 * authentication or ownership enforcement. It asserts the three-way policy now in force:</p>
 * <ul>
 *   <li><b>Anonymous</b> (no credentials) -> <b>401</b> on every endpoint;</li>
 *   <li><b>Authenticated non-owner</b> (a valid member token for a <em>different</em> member)
 *       -> <b>403</b>;</li>
 *   <li><b>Authenticated owner</b> (member token for the path member) and <b>trusted SERVICE</b>
 *       (shared internal token, admin/cross-member access) -> success (200/201).</li>
 * </ul>
 *
 * <p>Member access tokens are minted with the production {@link CallerAuthenticator#mintMemberToken}
 * so the test verifies the exact HMAC scheme the interceptor enforces (a forged/foreign token is
 * rejected). Positive cases deliberately exercise only the side-effect-light endpoints that make no
 * cross-service HTTP call (add-to-cart, remove-from-cart, order history, order lookup); the
 * preview/submit happy paths -- which require downstream stubs -- are covered by {@code OrderServiceIT}.
 * The negative (401/403) assertions cover preview/submit too, since the interceptor rejects those
 * before any controller or HTTP work runs.</p>
 *
 * <p>Runs against a real Testcontainers PostgreSQL loaded with {@code db/01_schema.sql} +
 * {@code db/03_seed_data.sql}; a single CONFIRMED order owned by {@link #OWNER_ID} is inserted in
 * {@link #loadSchemaAndSeed()} to exercise {@code GET /api/orders/{orderId}} ownership.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public class OrderResourceAccessControlIT {

    /** Test-only value for the now-required (no-default) shared service token (CWE-798 fix). */
    private static final String TEST_INTERNAL_TOKEN = "test-internal-token";

    /** Seeded member 2 (Robert Torres) - the resource owner in these tests. */
    private static final Long OWNER_ID = 2L;
    /** Seeded member 3 (Emily Chen) - an authenticated but NON-owning caller. */
    private static final Long OTHER_ID = 3L;
    /** Any product id for cart bodies; cart writes do not validate product existence. */
    private static final long PRODUCT_ID = 1L;

    /** Id of the CONFIRMED order owned by {@link #OWNER_ID}, inserted during schema/seed load. */
    private static long ownerOrderId;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // security.internal.token has no default in application.properties (CWE-798 fix); supply an
        // explicit value so the context starts and member tokens are minted/verified with it.
        registry.add("security.internal.token", () -> TEST_INTERNAL_TOKEN);
    }

    @BeforeAll
    static void loadSchemaAndSeed() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/01_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/03_seed_data.sql"));
            // Insert one CONFIRMED order owned by OWNER_ID so the order-lookup ownership policy can be
            // exercised (the seed contains no orders). RETURNING id captures the generated key.
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                         "INSERT INTO orders "
                                 + "(member_id, status, subtotal, discount_amount, shipping_cost, total, created_at) "
                                 + "VALUES (" + OWNER_ID + ", 'CONFIRMED', 45.85, 2.29, 5.99, 49.55, now()) "
                                 + "RETURNING id")) {
                rs.next();
                ownerOrderId = rs.getLong("id");
            }
        }
    }

    @Autowired
    private MockMvc mockMvc;

    /** Production authenticator, used to mint valid member access tokens exactly as the edge verifies them. */
    @Autowired
    private CallerAuthenticator callerAuthenticator;

    /** For post-test cleanup of any draft-cart rows created by positive add-to-cart cases. */
    @Autowired
    private OrderDraftItemRepository orderDraftItemRepository;

    @AfterEach
    void clearDraftCarts() {
        orderDraftItemRepository.deleteAll(orderDraftItemRepository.findByMemberId(OWNER_ID));
        orderDraftItemRepository.deleteAll(orderDraftItemRepository.findByMemberId(OTHER_ID));
    }

    // ----- request-builder helpers -----

    /** A valid add-to-cart JSON body. */
    private static String cartBody() {
        return "{\"productId\":" + PRODUCT_ID + ",\"quantity\":1}";
    }

    /** Adds member-token credentials (X-Auth-Member-Id + signed X-Auth-Member-Token) for {@code memberId}. */
    private MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder builder, Long memberId) {
        return builder
                .header(CallerAuthenticator.AUTH_MEMBER_ID_HEADER, memberId)
                .header(CallerAuthenticator.AUTH_MEMBER_TOKEN_HEADER,
                        callerAuthenticator.mintMemberToken(memberId));
    }

    /** Adds the trusted SERVICE credential (shared internal token). */
    private MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder builder) {
        return builder.header(InternalServiceAuthInterceptor.INTERNAL_TOKEN_HEADER, TEST_INTERNAL_TOKEN);
    }

    // ===================== 401 - anonymous on every endpoint =====================

    @Test
    void addToCartAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/orders/cart/{memberId}", OWNER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(cartBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removeFromCartAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/orders/cart/{memberId}/{productId}", OWNER_ID, PRODUCT_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void previewAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/orders/cart/{memberId}/preview", OWNER_ID).param("zip", "27601"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submitAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/orders/submit/{memberId}", OWNER_ID).param("zip", "27601"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void orderHistoryAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/orders/member/{memberId}", OWNER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getOrderAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/orders/{orderId}", ownerOrderId))
                .andExpect(status().isUnauthorized());
    }

    // ===================== 401 - malformed/forged member token =====================

    @Test
    void memberTokenWithWrongSignatureIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/orders/member/{memberId}", OWNER_ID)
                        .header(CallerAuthenticator.AUTH_MEMBER_ID_HEADER, OWNER_ID)
                        .header(CallerAuthenticator.AUTH_MEMBER_TOKEN_HEADER, "not-a-valid-token"))
                .andExpect(status().isUnauthorized());
    }

    // ===================== 403 - authenticated non-owner =====================

    @Test
    void addToCartAsNonOwnerIsForbidden() throws Exception {
        mockMvc.perform(asMember(post("/api/orders/cart/{memberId}", OWNER_ID), OTHER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(cartBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void removeFromCartAsNonOwnerIsForbidden() throws Exception {
        mockMvc.perform(asMember(
                        delete("/api/orders/cart/{memberId}/{productId}", OWNER_ID, PRODUCT_ID), OTHER_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void previewAsNonOwnerIsForbidden() throws Exception {
        mockMvc.perform(asMember(get("/api/orders/cart/{memberId}/preview", OWNER_ID), OTHER_ID)
                        .param("zip", "27601"))
                .andExpect(status().isForbidden());
    }

    @Test
    void submitAsNonOwnerIsForbidden() throws Exception {
        mockMvc.perform(asMember(post("/api/orders/submit/{memberId}", OWNER_ID), OTHER_ID)
                        .param("zip", "27601"))
                .andExpect(status().isForbidden());
    }

    @Test
    void orderHistoryAsNonOwnerIsForbidden() throws Exception {
        mockMvc.perform(asMember(get("/api/orders/member/{memberId}", OWNER_ID), OTHER_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void getOrderAsNonOwnerIsForbidden() throws Exception {
        // Member 3 is authenticated but does not own member 2's order -> 403 (not 404; the order exists).
        mockMvc.perform(asMember(get("/api/orders/{orderId}", ownerOrderId), OTHER_ID))
                .andExpect(status().isForbidden());
    }

    // ===================== 200/201 - authenticated owner =====================

    @Test
    void addToCartAsOwnerIsCreated() throws Exception {
        mockMvc.perform(asMember(post("/api/orders/cart/{memberId}", OWNER_ID), OWNER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(cartBody()))
                .andExpect(status().isCreated());
    }

    @Test
    void removeFromCartAsOwnerIsOk() throws Exception {
        mockMvc.perform(asMember(
                        delete("/api/orders/cart/{memberId}/{productId}", OWNER_ID, PRODUCT_ID), OWNER_ID))
                .andExpect(status().isOk());
    }

    @Test
    void orderHistoryAsOwnerIsOk() throws Exception {
        mockMvc.perform(asMember(get("/api/orders/member/{memberId}", OWNER_ID), OWNER_ID))
                .andExpect(status().isOk());
    }

    @Test
    void getOrderAsOwnerIsOk() throws Exception {
        mockMvc.perform(asMember(get("/api/orders/{orderId}", ownerOrderId), OWNER_ID))
                .andExpect(status().isOk());
    }

    // ===================== trusted SERVICE - cross-member access =====================

    @Test
    void orderHistoryAsServiceIsOk() throws Exception {
        mockMvc.perform(asService(get("/api/orders/member/{memberId}", OWNER_ID)))
                .andExpect(status().isOk());
    }

    @Test
    void getOrderAsServiceIsOk() throws Exception {
        mockMvc.perform(asService(get("/api/orders/{orderId}", ownerOrderId)))
                .andExpect(status().isOk());
    }

    @Test
    void addToCartAsServiceIsCreated() throws Exception {
        mockMvc.perform(asService(post("/api/orders/cart/{memberId}", OWNER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(cartBody()))
                .andExpect(status().isCreated());
    }
}
