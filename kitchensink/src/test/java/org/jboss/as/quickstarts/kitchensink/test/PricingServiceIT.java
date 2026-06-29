package org.jboss.as.quickstarts.kitchensink.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import org.jboss.as.quickstarts.kitchensink.service.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.service.PricingService;

/**
 * Integration tests for {@link PricingService} (the Java re-implementation of {@code calculate_price}).
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): rewritten from an Arquillian/JUnit 4
 * in-container test ({@code @RunWith(Arquillian.class)} + ShrinkWrap {@code @Deployment}) to a
 * {@code @SpringBootTest} + JUnit 5 test that loads the real application context against the
 * Testcontainers PostgreSQL database (schema and seed from {@code db/01_schema.sql} and
 * {@code db/03_seed_data.sql}). The collaborator is obtained via {@code @Autowired}. The invalid-combo
 * assertion now expects {@link InventoryNotFoundException} instead of the legacy {@code EJBException}.</p>
 *
 * <p>Seed reference: product 1 = Latex Exam Gloves (base_price $8.49); vendor 1 markup = 8%.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class PricingServiceIT {

    @Autowired
    private PricingService pricingService;

    /**
     * Test 1: basic price for product 1 / vendor 1 / qty 1 — must be positive and exceed the $8.49
     * base price (the vendor markup pushes the price above base).
     */
    @Test
    void testCalculatePriceBasic() {
        BigDecimal price = pricingService.calculatePrice(1L, 1L, 1);
        assertNotNull(price, "Price should not be null");
        assertTrue(price.compareTo(BigDecimal.ZERO) > 0, "Price should be positive");
        assertTrue(price.compareTo(new BigDecimal("8.49")) > 0,
            "Price with markup should exceed base price of $8.49");
    }

    /**
     * Test 2: the volume discount reduces the unit price at qty=100 versus qty=1.
     */
    @Test
    void testVolumeDiscountApplied() {
        BigDecimal priceQty1 = pricingService.calculatePrice(1L, 1L, 1);
        BigDecimal priceQty100 = pricingService.calculatePrice(1L, 1L, 100);

        assertNotNull(priceQty1, "Price qty=1 should not be null");
        assertNotNull(priceQty100, "Price qty=100 should not be null");
        assertTrue(priceQty100.compareTo(priceQty1) < 0,
            "Unit price at qty=100 should be less than unit price at qty=1 (15% volume discount)");
    }

    /**
     * Test 3: the volume-discount tiers reduce the unit price progressively:
     * qty=10 (2%) &lt; qty=1, qty=20 (5%) &lt; qty=10, qty=50 (10%) &lt; qty=20, qty=100 (15%) &lt; qty=50.
     */
    @Test
    void testVolumeDiscountTiers() {
        BigDecimal price1 = pricingService.calculatePrice(1L, 1L, 1);
        BigDecimal price10 = pricingService.calculatePrice(1L, 1L, 10);
        BigDecimal price20 = pricingService.calculatePrice(1L, 1L, 20);
        BigDecimal price50 = pricingService.calculatePrice(1L, 1L, 50);
        BigDecimal price100 = pricingService.calculatePrice(1L, 1L, 100);

        assertTrue(price10.compareTo(price1) < 0, "qty=10 (2% disc) < no-discount");
        assertTrue(price20.compareTo(price10) < 0, "qty=20 (5% disc) < qty=10 (2%)");
        assertTrue(price50.compareTo(price20) < 0, "qty=50 (10% disc) < qty=20 (5%)");
        assertTrue(price100.compareTo(price50) < 0, "qty=100 (15% disc) < qty=50 (10%)");
    }

    /**
     * Test 4: an invalid product/vendor combination throws {@link InventoryNotFoundException}
     * (the Java equivalent of the stored procedure's {@code RAISE EXCEPTION}, formerly surfaced as
     * an {@code EJBException}).
     */
    @Test
    void testInvalidVendorProductComboThrowsException() {
        assertThrows(InventoryNotFoundException.class,
            () -> pricingService.calculatePrice(99L, 99L, 1));
    }
}
