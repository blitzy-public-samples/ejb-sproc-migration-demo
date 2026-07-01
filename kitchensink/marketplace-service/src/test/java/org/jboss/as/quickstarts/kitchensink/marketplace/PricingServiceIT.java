package org.jboss.as.quickstarts.kitchensink.marketplace;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.jboss.as.quickstarts.kitchensink.marketplace.exception.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.marketplace.service.PricingService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link PricingService}, the pure-Java reimplementation of the
 * {@code calculate_price} stored procedure. The only test in marketplace-service.
 *
 * <p>This is the modern {@code @SpringBootTest} + Testcontainers (JUnit 5 / Jupiter) rewrite of
 * the monolith's legacy container-bundled JUnit-4 {@code PricingServiceIT}. It boots a real
 * {@code postgres:16} container, seeds it from the three authoritative {@code db/*.sql} scripts,
 * and runs the Spring context with {@code spring.jpa.hibernate.ddl-auto=validate} so Hibernate
 * validates the entity mappings against the frozen schema rather than regenerating it.</p>
 *
 * <p>The four tests assert the exact, behavior-preserving prices required by the AAP parity
 * conformance checks. All {@link BigDecimal} equality is expressed with
 * {@code compareTo(...) == 0} (never {@code equals}) because {@code PricingService} returns
 * scale-4 values and {@link BigDecimal#equals(Object)} is scale-sensitive.</p>
 *
 * <p>Boundary rule (AAP &sect;0.7.2): marketplace-service is self-contained for testing. The only
 * project imports are {@link PricingService} and {@link InventoryNotFoundException}; there are no
 * cross-service ({@code users}/{@code orders}) imports and no HTTP stubs.</p>
 */
@SpringBootTest
@Testcontainers
class PricingServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("kitchensink");

    /**
     * Wire the running container's JDBC coordinates into Spring and force
     * {@code ddl-auto=validate} (NEVER create) so the ordering is: schema scripts
     * loaded in {@link #seedDatabase()} -&gt; Hibernate validate at context load.
     *
     * <p>Method-reference suppliers are used so the values are resolved lazily at
     * context-load time, by which point the container is already running.</p>
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /**
     * Seed the container from the three authoritative scripts IN ORDER. Each file's FULL
     * text is executed as a SINGLE statement (NEVER split on ';') because
     * {@code 02_stored_procedures.sql} contains dollar-quoted PL/pgSQL bodies whose interior
     * semicolons would corrupt a naive splitter (which is also why
     * {@code PostgreSQLContainer.withInitScript(...)} is deliberately avoided). Module-relative
     * paths resolve because integration tests run with CWD = {@code marketplace-service/}.
     */
    @BeforeAll
    static void seedDatabase() throws Exception {
        String[] scripts = {
            "../db/01_schema.sql",
            "../db/02_stored_procedures.sql",
            "../db/03_seed_data.sql"
        };
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            for (String script : scripts) {
                String sql = Files.readString(Path.of(script), StandardCharsets.UTF_8);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
        }
    }

    @Autowired
    private PricingService pricingService;

    /** Test 1: basic price for product 1 / vendor 1 / qty 1 — positive, above base, exact parity. */
    @Test
    void testCalculatePriceBasic() {
        BigDecimal price = pricingService.calculatePrice(1L, 1L, 1);
        assertNotNull(price, "Price should not be null");
        assertTrue(price.compareTo(BigDecimal.ZERO) > 0, "Price should be positive");
        assertTrue(price.compareTo(new BigDecimal("8.49")) > 0,
                "Price with markup should exceed base price of $8.49");
        assertTrue(price.compareTo(new BigDecimal("9.1692")) == 0,
                "Unit price must equal 9.1692 (8.49 x 1.08 x 1.00)");
    }

    /** Test 2: volume discount lowers unit price at qty=100 vs qty=1 (exact parity both ends). */
    @Test
    void testVolumeDiscountApplied() {
        BigDecimal priceQty1 = pricingService.calculatePrice(1L, 1L, 1);
        BigDecimal priceQty100 = pricingService.calculatePrice(1L, 1L, 100);
        assertNotNull(priceQty1, "Price qty=1 should not be null");
        assertNotNull(priceQty100, "Price qty=100 should not be null");
        assertTrue(priceQty100.compareTo(priceQty1) < 0,
                "Unit price at qty=100 should be less than at qty=1 (15% volume discount)");
        assertTrue(priceQty1.compareTo(new BigDecimal("9.1692")) == 0, "qty=1 must equal 9.1692");
        assertTrue(priceQty100.compareTo(new BigDecimal("7.7938")) == 0, "qty=100 must equal 7.7938");
    }

    /** Test 3: all volume tiers strictly decreasing with exact parity values. */
    @Test
    void testVolumeDiscountTiers() {
        BigDecimal price1 = pricingService.calculatePrice(1L, 1L, 1);
        BigDecimal price10 = pricingService.calculatePrice(1L, 1L, 10);
        BigDecimal price20 = pricingService.calculatePrice(1L, 1L, 20);
        BigDecimal price50 = pricingService.calculatePrice(1L, 1L, 50);
        BigDecimal price100 = pricingService.calculatePrice(1L, 1L, 100);

        assertTrue(price10.compareTo(price1) < 0, "qty=10 (2%) < qty=1 (0%)");
        assertTrue(price20.compareTo(price10) < 0, "qty=20 (5%) < qty=10 (2%)");
        assertTrue(price50.compareTo(price20) < 0, "qty=50 (10%) < qty=20 (5%)");
        assertTrue(price100.compareTo(price50) < 0, "qty=100 (15%) < qty=50 (10%)");

        assertTrue(price1.compareTo(new BigDecimal("9.1692")) == 0, "qty=1 must equal 9.1692");
        assertTrue(price10.compareTo(new BigDecimal("8.9858")) == 0, "qty=10 must equal 8.9858");
        assertTrue(price20.compareTo(new BigDecimal("8.7107")) == 0, "qty=20 must equal 8.7107");
        assertTrue(price50.compareTo(new BigDecimal("8.2523")) == 0, "qty=50 must equal 8.2523");
        assertTrue(price100.compareTo(new BigDecimal("7.7938")) == 0, "qty=100 must equal 7.7938");
    }

    /** Test 4: missing (product, vendor) inventory throws the new domain exception (replaces the legacy container exception idiom). */
    @Test
    void testInvalidVendorProductComboThrowsException() {
        assertThrows(InventoryNotFoundException.class,
                () -> pricingService.calculatePrice(99L, 99L, 1));
    }
}
