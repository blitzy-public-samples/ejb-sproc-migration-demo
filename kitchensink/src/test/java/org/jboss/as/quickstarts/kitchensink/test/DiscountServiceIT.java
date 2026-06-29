package org.jboss.as.quickstarts.kitchensink.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import org.jboss.as.quickstarts.kitchensink.data.DiscountAuditRepository;
import org.jboss.as.quickstarts.kitchensink.service.DiscountService;
import org.jboss.as.quickstarts.kitchensink.service.PricingService;

/**
 * Integration tests for {@link DiscountService} (the Java re-implementation of
 * {@code apply_customer_discount}, including its {@code discount_audit} side effect).
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): rewritten from Arquillian/JUnit 4 to
 * {@code @SpringBootTest} + JUnit 5. This test is intentionally <strong>not</strong> {@code @Transactional}:
 * {@code calculateDiscount} commits a {@code discount_audit} row, and the audit-count assertion must see
 * that committed write, so the legacy {@code UserTransaction} begin/commit dance is replaced simply by
 * letting the service's own {@code @Transactional} boundary commit. Collaborators are {@code @Autowired};
 * the audit count uses {@link DiscountAuditRepository#count()} in place of a JPQL {@code COUNT} query.</p>
 *
 * <p>Seed reference: member 1 = Jane Smith (GOLD), member 2 = Robert Torres (SILVER),
 * member 3 = Emily Chen (BRONZE).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class DiscountServiceIT {

    @Autowired
    private DiscountService discountService;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private DiscountAuditRepository discountAuditRepository;

    /**
     * Test 1: a BRONZE member (member 3) receives ≈ 2% off a $100 base.
     */
    @Test
    void testBronzeMemberDiscountIsApproximatelyTwoPercent() {
        BigDecimal discount = discountService.calculateDiscount(3L, new BigDecimal("100.00"));
        assertNotNull(discount, "Discount should not be null");
        assertTrue(
            discount.compareTo(new BigDecimal("1.99")) >= 0
                && discount.compareTo(new BigDecimal("2.01")) <= 0,
            "BRONZE 2% discount on $100 should be between $1.99 and $2.01");
    }

    /**
     * Test 2: a GOLD member (member 1) receives ≈ 8% off a $100 base.
     */
    @Test
    void testGoldMemberDiscountIsApproximatelyEightPercent() {
        BigDecimal discount = discountService.calculateDiscount(1L, new BigDecimal("100.00"));
        assertNotNull(discount, "Discount should not be null");
        assertTrue(
            discount.compareTo(new BigDecimal("7.99")) >= 0
                && discount.compareTo(new BigDecimal("8.01")) <= 0,
            "GOLD 8% discount on $100 should be between $7.99 and $8.01");
    }

    /**
     * Test 3: each {@code calculateDiscount} call inserts exactly one {@code discount_audit} row.
     */
    @Test
    void testDiscountAuditRowCreated() {
        long beforeCount = discountAuditRepository.count();

        discountService.calculateDiscount(2L, new BigDecimal("50.00"));

        long afterCount = discountAuditRepository.count();
        assertEquals(beforeCount + 1, afterCount,
            "discount_audit should have exactly one more row after calculateDiscount()");
    }

    /**
     * Test 4: {@code getDiscountedLineTotal} (which uses the shared {@link PricingService}) is less than
     * the undiscounted {@code calculateLineTotal}, because a member discount has been applied.
     */
    @Test
    void testGetDiscountedLineTotalUsesSharedPricingService() {
        BigDecimal lineTotal = pricingService.calculateLineTotal(1L, 1L, 5);
        BigDecimal discountedLine = discountService.getDiscountedLineTotal(2L, 1L, 1L, 5);

        assertNotNull(lineTotal, "Line total should not be null");
        assertNotNull(discountedLine, "Discounted line total should not be null");
        assertTrue(discountedLine.compareTo(lineTotal) < 0,
            "Discounted line total must be less than undiscounted line total");
    }
}
