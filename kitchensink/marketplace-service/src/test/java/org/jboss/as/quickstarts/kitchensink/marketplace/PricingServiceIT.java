package org.jboss.as.quickstarts.kitchensink.marketplace;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;

import org.jboss.as.quickstarts.kitchensink.marketplace.exception.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.marketplace.service.PricingService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link PricingService} — the single integration test owned by
 * marketplace-service.
 *
 * <p><strong>Origin.</strong> This is a faithful migration of the legacy JBoss container-managed
 * JUnit&nbsp;4 integration test of the same name. The legacy Jakarta&nbsp;EE test harness
 * (the in-container deployment archive builder and the CDI-based field injection) has been replaced
 * by the Spring Boot 3.x test stack: {@code @SpringBootTest} boots the real application context and
 * Testcontainers provisions a disposable PostgreSQL instance. The four original test methods, their
 * names, and their observable assertions are preserved verbatim.</p>
 *
 * <p><strong>Self-contained (no downstream stubs).</strong> marketplace-service is a pure producer:
 * it serves HTTP but makes no outbound cross-service calls, so this test needs no HTTP client
 * stubbing of any kind (AAP §0.6.3 — downstream stub: None).</p>
 *
 * <p><strong>Parity, not procedures.</strong> The test proves that the Java {@link PricingService}
 * reproduces the numeric outputs of the original {@code calculate_price()} PL/pgSQL procedure when
 * run against real, seeded PostgreSQL data. Parity is asserted ONLY against {@code db/01_schema.sql}
 * + {@code db/03_seed_data.sql}; the separate PL/pgSQL procedure script is never loaded, because the
 * migrated service computes pricing entirely in Java with no native SQL (AAP §0.6.7).</p>
 *
 * <p><strong>Context auto-discovery.</strong> This class lives in the service's base package
 * {@code org.jboss.as.quickstarts.kitchensink.marketplace} so that {@code @SpringBootTest} discovers
 * the {@code @SpringBootApplication} configuration ({@code MarketplaceApplication}) by walking up the
 * package hierarchy — hence no explicit {@code classes = ...} attribute is required.</p>
 */
@SpringBootTest
@Testcontainers
public class PricingServiceIT {

    /**
     * Disposable PostgreSQL backing the integration test.
     *
     * <p>Declared {@code static} so the Testcontainers JUnit&nbsp;5 extension starts it once and
     * shares the single container across every test method in this class (started before
     * {@link #datasourceProperties(DynamicPropertyRegistry)} resolves and before
     * {@link #loadSchemaAndSeed()} runs). The {@code postgres:16-alpine} image is pre-pulled in the
     * build/CI environment.</p>
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Points the Spring {@code DataSource} at the running container.
     *
     * <p>Overrides the {@code spring.datasource.*} placeholders declared in the module's
     * {@code application.properties} ({@code ${DB_USERNAME}} / {@code ${DB_PASSWORD}}), so those
     * environment placeholders are never evaluated during the test. Registration happens before the
     * application context is created.</p>
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /**
     * Loads the authoritative schema and seed data into the container BEFORE the Spring context starts.
     *
     * <p>The module sets {@code spring.jpa.hibernate.ddl-auto=validate}, so the {@code products},
     * {@code vendors}, and {@code vendor_inventory} tables must already exist when Hibernate validates
     * the owned entity mappings at context startup. A direct JDBC connection to the container is used
     * (rather than the Spring {@code DataSource}) precisely because this static {@code @BeforeAll}
     * method runs before the lazily-initialised context is wired into the test instance.</p>
     *
     * <p>Load order is strict: schema first ({@code 01_schema.sql}), then seed ({@code 03_seed_data.sql}).
     * The separate PL/pgSQL procedure script is intentionally NEVER loaded — the migrated
     * marketplace-service computes pricing in Java with no native SQL (AAP §0.6.3, §0.6.7). Both
     * scripts are resolved from the test classpath ({@code src/test/resources/db/}).</p>
     */
    @BeforeAll
    static void loadSchemaAndSeed() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // Schema FIRST, then seed. The PL/pgSQL procedure script is intentionally never loaded.
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/01_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/03_seed_data.sql"));
        }
    }

    /** Field injection is acceptable in tests; the bean is supplied by the booted Spring context. */
    @Autowired
    private PricingService pricingService;

    /**
     * Test 1 — basic price calculation for product 1, vendor 1, qty 1.
     *
     * <p>Seed: product 1 = "Latex Exam Gloves Medium 100ct", {@code base_price = 8.49};
     * {@code vendor_inventory(vendor_id=1, product_id=1)} {@code markup_percent = 8.00}. With no
     * volume discount at qty 1 the unit price is {@code round(8.49 × 1.08, 4) = 9.1692}, which must be
     * positive and strictly greater than the base price. The exact-parity assertion locks in the
     * procedure-equivalent output value.</p>
     */
    @Test
    public void testCalculatePriceBasic() {
        BigDecimal price = pricingService.calculatePrice(1L, 1L, 1);
        Assertions.assertNotNull(price, "Price should not be null");
        Assertions.assertTrue(price.compareTo(BigDecimal.ZERO) > 0, "Price should be positive");
        Assertions.assertTrue(price.compareTo(new BigDecimal("8.49")) > 0,
            "Price with markup should exceed base price of $8.49");
        Assertions.assertEquals(0, price.compareTo(new BigDecimal("9.1692")),
            "Expected exact parity unit price 9.1692 (8.49 x 1.08)");
    }

    /**
     * Test 2 — the volume discount reduces the unit price at qty 100 (15% tier) versus qty 1 (no discount).
     */
    @Test
    public void testVolumeDiscountApplied() {
        BigDecimal priceQty1 = pricingService.calculatePrice(1L, 1L, 1);
        BigDecimal priceQty100 = pricingService.calculatePrice(1L, 1L, 100);
        Assertions.assertNotNull(priceQty1, "Price qty=1 should not be null");
        Assertions.assertNotNull(priceQty100, "Price qty=100 should not be null");
        Assertions.assertTrue(priceQty100.compareTo(priceQty1) < 0,
            "Unit price at qty=100 should be less than unit price at qty=1 (15% volume discount)");
    }

    /**
     * Test 3 — every volume-discount tier applies progressively.
     *
     * <p>Tiers (preserved exactly): qty&ge;10 &rarr; 2%, qty&ge;20 &rarr; 5%, qty&ge;50 &rarr; 10%,
     * qty&ge;100 &rarr; 15%. The resulting unit prices are therefore strictly decreasing across the
     * quantities 1 &gt; 10 &gt; 20 &gt; 50 &gt; 100.</p>
     */
    @Test
    public void testVolumeDiscountTiers() {
        BigDecimal price1 = pricingService.calculatePrice(1L, 1L, 1);
        BigDecimal price10 = pricingService.calculatePrice(1L, 1L, 10);
        BigDecimal price20 = pricingService.calculatePrice(1L, 1L, 20);
        BigDecimal price50 = pricingService.calculatePrice(1L, 1L, 50);
        BigDecimal price100 = pricingService.calculatePrice(1L, 1L, 100);
        Assertions.assertTrue(price10.compareTo(price1) < 0, "qty=10 (2% disc) < no-discount");
        Assertions.assertTrue(price20.compareTo(price10) < 0, "qty=20 (5% disc) < qty=10 (2%)");
        Assertions.assertTrue(price50.compareTo(price20) < 0, "qty=50 (10% disc) < qty=20 (5%)");
        Assertions.assertTrue(price100.compareTo(price50) < 0, "qty=100 (15% disc) < qty=50 (10%)");
    }

    /**
     * Test 4 — an invalid product/vendor combination throws {@link InventoryNotFoundException}.
     *
     * <p>Product 99 is absent from the seed (only products 1–10 exist), so {@link PricingService}
     * raises {@code InventoryNotFoundException} — the marketplace-local replacement for the SQL
     * {@code RAISE EXCEPTION ... ERRCODE 'P0001'} and the legacy container-exception not-found path.</p>
     */
    @Test
    public void testInvalidVendorProductComboThrowsException() {
        Assertions.assertThrows(InventoryNotFoundException.class,
            () -> pricingService.calculatePrice(99L, 99L, 1));
    }
}
