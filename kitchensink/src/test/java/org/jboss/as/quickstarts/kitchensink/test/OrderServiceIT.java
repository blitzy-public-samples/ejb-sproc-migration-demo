package org.jboss.as.quickstarts.kitchensink.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.data.OrderDraftItemRepository;
import org.jboss.as.quickstarts.kitchensink.data.OrderRepository;
import org.jboss.as.quickstarts.kitchensink.model.Order;
import org.jboss.as.quickstarts.kitchensink.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for {@link OrderService} (migrated from Arquillian/JUnit 4 to
 * Spring Boot @SpringBootTest / JUnit 5). NOT transactional: testMemberTotalSpendIncreasesAfterOrder
 * must observe submitOrder's committed total_spend on a fresh read. Isolation is provided by
 * the @BeforeEach/@AfterEach draft-cart cleanup for member 2.
 */
@SpringBootTest
@ActiveProfiles("test")
public class OrderServiceIT {

    // Use member 2 (Robert Torres, SILVER) to avoid conflicting with other tests
    private static final Long TEST_MEMBER_ID = 2L;
    private static final String TEST_ZIP     = "27601";

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderDraftItemRepository orderDraftItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private MemberRepository memberRepository;

    @BeforeEach
    public void cleanCartBefore() {
        orderDraftItemRepository.deleteByMemberId(TEST_MEMBER_ID);
    }

    @AfterEach
    public void cleanCartAfter() {
        orderDraftItemRepository.deleteByMemberId(TEST_MEMBER_ID);
    }

    /**
     * Test 1: addToCart() should persist a new order_draft_items row.
     */
    @Test
    public void testAddToCartInsertsRow() {
        orderService.addToCart(TEST_MEMBER_ID, 1L, 5);
        assertTrue(orderDraftItemRepository.findByMemberId(TEST_MEMBER_ID).size() >= 1,
            "Cart should have at least 1 item after addToCart()");
    }

    /**
     * Test 2: previewOrder() on a non-empty cart should return a non-zero total.
     */
    @Test
    public void testPreviewOrderReturnsNonZeroTotal() {
        orderService.addToCart(TEST_MEMBER_ID, 1L, 5);
        orderService.addToCart(TEST_MEMBER_ID, 3L, 10);

        OrderService.OrderPreview preview = orderService.previewOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        assertNotNull(preview, "Preview should not be null");
        assertTrue(preview.getSubtotal().compareTo(BigDecimal.ZERO) > 0, "Subtotal should be > 0");
        assertTrue(preview.getTotal().compareTo(BigDecimal.ZERO) > 0, "Total should be > 0");
        assertFalse(preview.getItems().isEmpty(), "Items list should not be empty");
    }

    /**
     * Test 3: submitOrder() should create a CONFIRMED order record.
     */
    @Test
    public void testSubmitOrderCreatesConfirmedOrder() {
        orderService.addToCart(TEST_MEMBER_ID, 2L, 3);

        Long orderId = orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);
        assertNotNull(orderId, "Order ID should not be null after submitOrder()");

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertEquals("CONFIRMED", order.getStatus(), "Order status should be CONFIRMED");
        assertEquals(TEST_MEMBER_ID, order.getMemberId(), "Order member ID should match");
    }

    /**
     * Test 4: submitOrder() should clear the member's draft cart.
     */
    @Test
    public void testSubmitOrderClearsDraftCart() {
        orderService.addToCart(TEST_MEMBER_ID, 1L, 2);
        orderService.addToCart(TEST_MEMBER_ID, 4L, 1);

        orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        assertEquals(0, orderDraftItemRepository.findByMemberId(TEST_MEMBER_ID).size(),
            "Draft cart should be empty after submitOrder()");
    }

    /**
     * Test 5: submitOrder() should increase the member's total_spend.
     */
    @Test
    public void testMemberTotalSpendIncreasesAfterOrder() {
        BigDecimal spendBefore = memberRepository.findById(TEST_MEMBER_ID).orElseThrow().getTotalSpend();

        orderService.addToCart(TEST_MEMBER_ID, 5L, 2);
        orderService.submitOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        BigDecimal spendAfter = memberRepository.findById(TEST_MEMBER_ID).orElseThrow().getTotalSpend();

        assertTrue(spendAfter.compareTo(spendBefore) > 0,
            "total_spend should increase after submitOrder()");
    }
}
