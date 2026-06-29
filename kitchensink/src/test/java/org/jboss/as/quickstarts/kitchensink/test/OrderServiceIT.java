package org.jboss.as.quickstarts.kitchensink.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

    @Autowired
    private EntityManagerFactory entityManagerFactory;

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

    /**
     * Test 6 (correctness): a multi-line cart preview must produce one line per cart entry, each with a
     * selected vendor and positive unit price/line total, and a subtotal equal to the exact sum of the
     * per-line totals. This guards the batch/projection orchestration refactor (the order-orchestration
     * N+1 fix) against any per-line accumulation error.
     */
    @Test
    public void testMultiLineCartPreviewComputesPerLineTotals() {
        orderService.addToCart(TEST_MEMBER_ID, 1L, 5);
        orderService.addToCart(TEST_MEMBER_ID, 3L, 10);
        orderService.addToCart(TEST_MEMBER_ID, 5L, 2);

        OrderService.OrderPreview preview = orderService.previewOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        assertNotNull(preview, "Preview should not be null");
        assertEquals(3, preview.getItems().size(), "Preview should contain one line per cart entry");

        BigDecimal sumOfLines = BigDecimal.ZERO;
        for (OrderService.LineItemPreview line : preview.getItems()) {
            assertNotNull(line.getVendorId(), "Each line must have a selected vendor");
            assertTrue(line.getUnitPrice().compareTo(BigDecimal.ZERO) > 0,
                "Each line unit price must be > 0");
            assertTrue(line.getLineTotal().compareTo(BigDecimal.ZERO) > 0,
                "Each line total must be > 0");
            sumOfLines = sumOfLines.add(line.getLineTotal());
        }
        // Subtotal must equal the sum of the per-line totals (batch refactor must not corrupt accumulation).
        assertEquals(0, preview.getSubtotal().compareTo(sumOfLines),
            "Subtotal must equal the sum of per-line totals");
    }

    /**
     * Test 7 (performance regression guard): order orchestration must NOT exhibit N+1 query
     * amplification. The seed data stocks every product at all five vendors, so the former
     * per-candidate implementation issued roughly {@code 2N+1} queries PER LINE (≈19/line, ≈60 for this
     * three-line cart): one inventory lookup, then per candidate a {@code calculate_price} that reloaded
     * product + inventory plus a per-candidate vendor lookup, then a redundant re-price and a per-line
     * product weight reload. The batch/projection implementation issues a bounded number of statements
     * that does NOT grow with the vendor count — one candidate-projection query per line, a single batch
     * product-weight load, and the fixed member/discount/shipping reads plus one audit insert. We assert
     * a robust ceiling well below the old count; a breach signals the N+1 pattern has returned.
     */
    @Test
    public void testMultiLineCartPreviewDoesNotScalePerVendor() {
        orderService.addToCart(TEST_MEMBER_ID, 1L, 5);
        orderService.addToCart(TEST_MEMBER_ID, 3L, 10);
        orderService.addToCart(TEST_MEMBER_ID, 5L, 2);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        OrderService.OrderPreview preview = orderService.previewOrder(TEST_MEMBER_ID, TEST_ZIP, false);

        assertNotNull(preview, "Preview should not be null");
        assertEquals(3, preview.getItems().size(), "Preview should contain one line per cart entry");

        long statements = statistics.getPrepareStatementCount();
        // New implementation is ≈10-13 statements for a 3-line cart; the old per-candidate path was ≈60.
        // A ceiling of 20 cleanly separates the two while leaving headroom against incidental variation.
        assertTrue(statements < 20,
            "Order preview should not exhibit N+1 query amplification; prepared statements="
                + statements + " (expected < 20)");
    }
}
