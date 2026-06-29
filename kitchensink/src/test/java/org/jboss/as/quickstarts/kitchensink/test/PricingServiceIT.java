package org.jboss.as.quickstarts.kitchensink.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.jboss.as.quickstarts.kitchensink.service.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.service.PricingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for {@link PricingService} (migrated from Arquillian/JUnit 4 to
 * Spring Boot @SpringBootTest / JUnit 5). Validates the Java re-implementation of the
 * former calculate_price stored procedure against the seeded test database.
 */
@SpringBootTest
@ActiveProfiles("test")
public class PricingServiceIT {

    @Autowired
    private PricingService pricingService;

    /**
     * Test 1: Basic price calculation for product 1, vendor 1, qty 1.
     * Result must be positive and greater than the product's base price ($8.49).
     */
    @Test
    public void testCalculatePriceBasic() {
        // Seed: product 1 = Latex Exam Gloves, base_price = $8.49, vendor 1 markup = 8%
        BigDecimal price = pricingService.calculatePrice(1L, 1L, 1);
        assertNotNull(price, "Price should not be null");
        assertTrue(price.compareTo(BigDecimal.ZERO) > 0, "Price should be positive");
        assertTrue(price.compareTo(new BigDecimal("8.49")) > 0,
            "Price with markup should exceed base price of $8.49");
    }

    /**
     * Test 2: Volume discount should reduce price at qty=100 vs qty=1.
     */
    @Test
    public void testVolumeDiscountApplied() {
        BigDecimal priceQty1   = pricingService.calculatePrice(1L, 1L, 1);
        BigDecimal priceQty100 = pricingService.calculatePrice(1L, 1L, 100);

        assertNotNull(priceQty1, "Price qty=1 should not be null");
        assertNotNull(priceQty100, "Price qty=100 should not be null");
        assertTrue(priceQty100.compareTo(priceQty1) < 0,
            "Unit price at qty=100 should be less than unit price at qty=1 (15% volume discount)");
    }

    /**
     * Test 3: All volume discount tiers applied progressively.
     */
    @Test
    public void testVolumeDiscountTiers() {
        BigDecimal price1   = pricingService.calculatePrice(1L, 1L, 1);
        BigDecimal price10  = pricingService.calculatePrice(1L, 1L, 10);
        BigDecimal price20  = pricingService.calculatePrice(1L, 1L, 20);
        BigDecimal price50  = pricingService.calculatePrice(1L, 1L, 50);
        BigDecimal price100 = pricingService.calculatePrice(1L, 1L, 100);

        assertTrue(price10.compareTo(price1) < 0, "qty=10 (2% disc) < no-discount");
        assertTrue(price20.compareTo(price10) < 0, "qty=20 (5% disc) < qty=10 (2%)");
        assertTrue(price50.compareTo(price20) < 0, "qty=50 (10% disc) < qty=20 (5%)");
        assertTrue(price100.compareTo(price50) < 0, "qty=100 (15% disc) < qty=50 (10%)");
    }

    /**
     * Test 4: Invalid product/vendor combination should throw InventoryNotFoundException
     * (replaces the former EJBException wrapping the stored-procedure RAISE EXCEPTION).
     */
    @Test
    public void testInvalidVendorProductComboThrowsException() {
        assertThrows(InventoryNotFoundException.class,
            () -> pricingService.calculatePrice(99L, 99L, 1));
    }
}
