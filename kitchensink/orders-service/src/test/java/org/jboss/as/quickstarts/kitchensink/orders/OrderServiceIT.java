package org.jboss.as.quickstarts.kitchensink.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.jboss.as.quickstarts.kitchensink.orders.exception.NoEligibleVendorException;
import org.jboss.as.quickstarts.kitchensink.orders.model.Order;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderDraftItemRepository;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderItemRepository;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderRepository;
import org.jboss.as.quickstarts.kitchensink.orders.service.OrderService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    @MockitoBean
    private MarketplaceClient marketplaceClient;

    @MockitoBean
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
        // Default product weight is 0 for tests that do not exercise weight; the weight-accumulation
        // test overrides this for its specific product (review C5).
        when(marketplaceClient.getProductWeight(anyLong())).thenReturn(BigDecimal.ZERO);
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
    void testSubmitOrderIncrementsMemberTotalSpendBySubtotal() {
        orderService.addToCart(TEST_MEMBER_ID, 5L, 2);

        Long orderId = orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertTrue(order.getSubtotal().signum() > 0, "persisted order subtotal should be positive");

        // Source-A process_order side-effect (review C4 / AAP §0.6.1): member.total_spend is incremented
        // by the order SUBTOTAL (Source A overrides the Source-B SQL, which added the total). orders-service
        // owns no member row (boundary rule), so it performs the increment over HTTP via UsersClient; verify
        // submitOrder called it exactly once with the order subtotal.
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(usersClient).incrementMemberTotalSpend(eq(TEST_MEMBER_ID), amountCaptor.capture());
        assertEquals(0, order.getSubtotal().compareTo(amountCaptor.getValue()),
                "total_spend must be incremented by the order subtotal (Source-A override of process_order)");
    }

    @Test
    void testSubmitOrderAccumulatesProductWeightForShipping() {
        // Override the default zero-weight stub: product 5 weighs 3.00 lb each; qty 5 -> 15.00 lb total.
        when(marketplaceClient.getProductWeight(5L)).thenReturn(new BigDecimal("3.00"));
        orderService.addToCart(TEST_MEMBER_ID, 5L, 5);

        Long orderId = orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        Order order = orderRepository.findById(orderId).orElseThrow();
        // ZIP 27601 -> Southeast zone @ 0.95/lb; shipping = GREATEST(5.99, 0.95 * 15.00) = 14.25.
        // With the pre-fix behavior (weight hard-zeroed) this would have been the 5.99 floor, so this
        // assertion specifically proves weight is now accumulated from the marketplace read (review C5).
        assertEquals(0, new BigDecimal("14.25").compareTo(order.getShippingCost()),
                "shipping must reflect accumulated cart weight (0.95/lb * 15.00 lb = 14.25), not the 5.99 floor");
    }

    @Test
    void testSubmitOrderWithNoEligibleVendorThrowsAndPersistsNothing() {
        // Product 5 has no eligible vendor (selectBestVendor -> null). The order must abort with
        // NoEligibleVendorException rather than silently dropping the cart line (review M1).
        when(marketplaceClient.selectBestVendor(eq(5L), anyInt())).thenReturn(null);
        orderService.addToCart(TEST_MEMBER_ID, 5L, 2);

        assertThrows(NoEligibleVendorException.class,
                () -> orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false));

        // The exception is thrown during orchestration, before any persistence and before step 7, so the
        // @Transactional submit rolls back: the lifetime-spend increment must never have been attempted.
        verify(usersClient, never()).incrementMemberTotalSpend(anyLong(), any());
    }
}
