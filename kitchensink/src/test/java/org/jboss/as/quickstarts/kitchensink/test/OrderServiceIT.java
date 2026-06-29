package org.jboss.as.quickstarts.kitchensink.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.data.OrderDraftItemRepository;
import org.jboss.as.quickstarts.kitchensink.data.OrderRepository;
import org.jboss.as.quickstarts.kitchensink.model.Member;
import org.jboss.as.quickstarts.kitchensink.model.Order;
import org.jboss.as.quickstarts.kitchensink.service.OrderService;

/**
 * Integration tests for {@link OrderService} (the unified {@code orchestrateOrder} path that replaces
 * the legacy {@code process_order} delegation).
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): rewritten from Arquillian/JUnit 4 to
 * {@code @SpringBootTest} + JUnit 5. The class is {@code @Transactional} so every test rolls back at the
 * end — this isolates the tests, protects the externally-seeded member 2 (Robert Torres, SILVER) from
 * permanent {@code total_spend} changes or leftover orders, and replaces the legacy manual
 * {@code UserTransaction} bookkeeping. A {@code @BeforeEach} clears member 2's draft cart for a clean
 * starting state. Data is read back through the Spring Data repositories within the same transaction.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrderServiceIT {

    /** Member 2 (Robert Torres, SILVER) is used to avoid conflicting with other tests. */
    private static final Long TEST_MEMBER_ID = 2L;
    private static final String TEST_ZIP = "27601";

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private OrderDraftItemRepository orderDraftItemRepository;

    @BeforeEach
    void cleanCart() {
        orderDraftItemRepository.deleteByMemberId(TEST_MEMBER_ID);
    }

    /**
     * Test 1: {@code addToCart} persists a new {@code order_draft_items} row.
     */
    @Test
    void testAddToCartInsertsRow() {
        orderService.addToCart(TEST_MEMBER_ID, 1L, 5);

        long count = orderDraftItemRepository.findByMemberId(TEST_MEMBER_ID).size();
        assertTrue(count >= 1, "Cart should have at least 1 item after addToCart()");
    }

    /**
     * Test 2: {@code previewOrder} on a non-empty cart returns positive subtotal and total and a
     * non-empty item list.
     */
    @Test
    void testPreviewOrderReturnsNonZeroTotal() {
        orderService.addToCart(TEST_MEMBER_ID, 1L, 5);
        orderService.addToCart(TEST_MEMBER_ID, 3L, 10);

        OrderService.OrderPreview preview = orderService.previewOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        assertNotNull(preview, "Preview should not be null");
        assertTrue(preview.getSubtotal().compareTo(BigDecimal.ZERO) > 0, "Subtotal should be > 0");
        assertTrue(preview.getTotal().compareTo(BigDecimal.ZERO) > 0, "Total should be > 0");
        assertFalse(preview.getItems().isEmpty(), "Items list should not be empty");
    }

    /**
     * Test 3: {@code submitOrder} creates a CONFIRMED order record for the member.
     */
    @Test
    void testSubmitOrderCreatesConfirmedOrder() {
        orderService.addToCart(TEST_MEMBER_ID, 2L, 3);

        Long orderId = orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);
        assertNotNull(orderId, "Order ID should not be null after submitOrder()");

        Order order = orderRepository.findById(orderId).orElse(null);
        assertNotNull(order, "Order entity should exist in DB");
        assertEquals("CONFIRMED", order.getStatus(), "Order status should be CONFIRMED");
        assertEquals(TEST_MEMBER_ID, order.getMemberId(), "Order member ID should match");
    }

    /**
     * Test 4: {@code submitOrder} clears the member's draft cart.
     */
    @Test
    void testSubmitOrderClearsDraftCart() {
        orderService.addToCart(TEST_MEMBER_ID, 1L, 2);
        orderService.addToCart(TEST_MEMBER_ID, 4L, 1);

        orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        long remaining = orderDraftItemRepository.findByMemberId(TEST_MEMBER_ID).size();
        assertEquals(0L, remaining, "Draft cart should be empty after submitOrder()");
    }

    /**
     * Test 5: {@code submitOrder} increases the member's {@code total_spend}.
     */
    @Test
    void testMemberTotalSpendIncreasesAfterOrder() {
        Member memberBefore = memberRepository.findById(TEST_MEMBER_ID).orElseThrow();
        BigDecimal spendBefore = memberBefore.getTotalSpend();

        orderService.addToCart(TEST_MEMBER_ID, 5L, 2);
        orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        Member memberAfter = memberRepository.findById(TEST_MEMBER_ID).orElseThrow();
        BigDecimal spendAfter = memberAfter.getTotalSpend();

        assertTrue(spendAfter.compareTo(spendBefore) > 0,
            "total_spend should increase after submitOrder()");
    }
}
