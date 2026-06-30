package org.jboss.as.quickstarts.kitchensink.orders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.jboss.as.quickstarts.kitchensink.orders.client.MarketplaceClient;
import org.jboss.as.quickstarts.kitchensink.orders.client.UsersClient;
import org.jboss.as.quickstarts.kitchensink.orders.model.Order;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderDraftItemRepository;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderItemRepository;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderRepository;
import org.jboss.as.quickstarts.kitchensink.orders.service.OrderService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@code OrderService}, rewritten from the monolith Arquillian test into the
 * orders-service {@code @SpringBootTest} + Testcontainers pattern.
 *
 * <p>The class is intentionally NOT {@code @Transactional}: the production {@code addToCart}/
 * {@code submitOrder} methods are {@code @Transactional} and must see committed rows. Cross-service
 * calls are exercised by stubbing the {@code MarketplaceClient}/{@code UsersClient} gateways
 * (boundary rule: no marketplace/users classes are imported).</p>
 */
@SpringBootTest
@Testcontainers
class OrderServiceIT {

    private static final Long TEST_MEMBER_ID = 2L;
    private static final String TEST_ZIP = "27601";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("kitchensink");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeAll
    static void seedDatabase() throws Exception {
        String[] scripts = {"../db/01_schema.sql", "../db/02_stored_procedures.sql", "../db/03_seed_data.sql"};
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            for (String script : scripts) {
                String sql = Files.readString(Path.of(script), StandardCharsets.UTF_8);
                statement.execute(sql);
            }
        }
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OrderDraftItemRepository orderDraftItemRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockBean
    private MarketplaceClient marketplaceClient;

    @MockBean
    private UsersClient usersClient;

    @BeforeEach
    void setUp() {
        // Clear member 2's draft cart inside a transaction. The bulk @Modifying delete requires an
        // active transaction, and this test class is intentionally not @Transactional.
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> orderDraftItemRepository.deleteByMemberId(TEST_MEMBER_ID));

        // FK-safe cross-service stubs: vendor 1 is a real seeded vendor so order_items.vendor_id satisfies
        // its foreign key; a positive unit price guarantees non-zero subtotal/total; member 2 -> SILVER.
        when(marketplaceClient.selectBestVendor(anyLong(), anyInt())).thenReturn(1L);
        when(marketplaceClient.getPrice(anyLong(), anyLong(), anyInt())).thenReturn(new BigDecimal("9.99"));
        when(usersClient.getMemberTier(TEST_MEMBER_ID)).thenReturn("SILVER");
    }

    @Test
    void testAddToCartInsertsRow() {
        orderService.addToCart(TEST_MEMBER_ID, 1L, 5);

        assertFalse(orderDraftItemRepository.findByMemberId(TEST_MEMBER_ID).isEmpty(),
                "addToCart should insert at least one draft cart row for the member");
    }

    @Test
    void testPreviewOrderReturnsNonZeroTotal() {
        orderService.addToCart(TEST_MEMBER_ID, 1L, 5);
        orderService.addToCart(TEST_MEMBER_ID, 3L, 10);

        OrderService.OrderPreview preview = orderService.previewOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        assertTrue(preview.getSubtotal().signum() > 0, "preview subtotal should be greater than zero");
        assertTrue(preview.getTotal().signum() > 0, "preview total should be greater than zero");
        assertFalse(preview.getItems().isEmpty(), "preview should contain at least one line item");
    }

    @Test
    void testSubmitOrderCreatesConfirmedOrder() {
        orderService.addToCart(TEST_MEMBER_ID, 2L, 3);

        Long orderId = orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertTrue("CONFIRMED".equals(order.getStatus()), "submitted order status should be CONFIRMED");
        assertTrue(TEST_MEMBER_ID.equals(order.getMemberId()), "submitted order should belong to the member");
    }

    @Test
    void testSubmitOrderClearsDraftCart() {
        orderService.addToCart(TEST_MEMBER_ID, 1L, 2);
        orderService.addToCart(TEST_MEMBER_ID, 4L, 1);

        orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        assertTrue(orderDraftItemRepository.findByMemberId(TEST_MEMBER_ID).isEmpty(),
                "submitOrder should clear the member's draft cart");
    }

    @Test
    void testSubmitOrderRecordsPositiveSubtotal() {
        orderService.addToCart(TEST_MEMBER_ID, 5L, 2);

        Long orderId = orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertTrue(order.getSubtotal().signum() > 0,
                "persisted order subtotal should be positive (orders-service does not own the member row,"
                        + " so the legacy member.total_spend increment assertion is replaced)");
    }
}
