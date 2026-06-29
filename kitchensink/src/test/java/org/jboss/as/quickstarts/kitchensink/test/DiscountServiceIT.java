package org.jboss.as.quickstarts.kitchensink.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.jboss.as.quickstarts.kitchensink.data.DiscountAuditRepository;
import org.jboss.as.quickstarts.kitchensink.service.DiscountService;
import org.jboss.as.quickstarts.kitchensink.service.PricingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for {@link DiscountService} (migrated from Arquillian/JUnit 4 to
 * Spring Boot @SpringBootTest / JUnit 5). NOT transactional: testDiscountAuditRowCreated
 * relies on the committed discount_audit row being visible to a fresh repository count.
 */
@SpringBootTest
@ActiveProfiles("test")
public class DiscountServiceIT {

    @Autowired
    private DiscountService discountService;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private DiscountAuditRepository discountAuditRepository;

    /**
     * Test 1: BRONZE member (member 3) discount on $100 base should be approximately 2%.
     */
    @Test
    public void testBronzeMemberDiscountIsApproximatelyTwoPercent() {
        BigDecimal baseTotal = new BigDecimal("100.00");
        BigDecimal discount = discountService.calculateDiscount(3L, baseTotal);
        assertNotNull(discount, "Discount should not be null");
        assertTrue(
            discount.compareTo(new BigDecimal("1.99")) >= 0 &&
            discount.compareTo(new BigDecimal("2.01")) <= 0,
            "BRONZE 2% discount on $100 should be between $1.99 and $2.01");
    }

    /**
     * Test 2: GOLD member (member 1) discount on $100 base should be approximately 8%.
     */
    @Test
    public void testGoldMemberDiscountIsApproximatelyEightPercent() {
        BigDecimal baseTotal = new BigDecimal("100.00");
        BigDecimal discount = discountService.calculateDiscount(1L, baseTotal);
        assertNotNull(discount, "Discount should not be null");
        assertTrue(
            discount.compareTo(new BigDecimal("7.99")) >= 0 &&
            discount.compareTo(new BigDecimal("8.01")) <= 0,
            "GOLD 8% discount on $100 should be between $7.99 and $8.01");
    }

    /**
     * Test 3: Each call to calculateDiscount() should insert exactly one new discount_audit row.
     */
    @Test
    public void testDiscountAuditRowCreated() {
        long before = discountAuditRepository.count();
        discountService.calculateDiscount(2L, new BigDecimal("50.00"));
        long after = discountAuditRepository.count();
        assertEquals(before + 1, after,
            "discount_audit should have exactly one more row after calculateDiscount()");
    }

    /**
     * Test 4: getDiscountedLineTotal() result must be less than calculateLineTotal()
     * because a discount was applied via the shared PricingService dependency.
     */
    @Test
    public void testGetDiscountedLineTotalUsesSharedPricingService() {
        BigDecimal lineTotal      = pricingService.calculateLineTotal(1L, 1L, 5);
        BigDecimal discountedLine = discountService.getDiscountedLineTotal(2L, 1L, 1L, 5);

        assertNotNull(lineTotal, "Line total should not be null");
        assertNotNull(discountedLine, "Discounted line total should not be null");
        assertTrue(discountedLine.compareTo(lineTotal) < 0,
            "Discounted line total must be less than undiscounted line total");
    }
}
