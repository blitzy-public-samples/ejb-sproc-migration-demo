package org.jboss.as.quickstarts.kitchensink.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.jboss.as.quickstarts.kitchensink.marketplace.model.Product;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.Vendor;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.VendorInventory;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.VendorInventoryId;
import org.jboss.as.quickstarts.kitchensink.marketplace.repository.ProductRepository;
import org.jboss.as.quickstarts.kitchensink.marketplace.repository.VendorInventoryRepository;
import org.jboss.as.quickstarts.kitchensink.marketplace.repository.VendorRepository;
import org.jboss.as.quickstarts.kitchensink.marketplace.service.VendorSelectionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link VendorSelectionService}, the pure-Java reimplementation of the
 * {@code select_best_vendor} stored procedure, proving Source-A MAXIMIZATION semantics
 * (AAP &sect;0.6.1 / &sect;0.7.3) over HTTP-consumable behavior.
 *
 * <p><b>Why a hand-built fixture (review C1/C2/C3):</b> the authoritative seed data
 * ({@code db/03_seed_data.sql}) is intentionally <i>monotonic</i> — the cheapest vendor is also the
 * highest-rated/fastest for every seeded product — so a cheapest-first (price-sorted) ordering and
 * the Source-A score-ranked ordering coincide and CANNOT distinguish the two implementations. This
 * test therefore inserts a <i>divergent</i> fixture where the cheaper vendor is lower-rated/slower
 * and the pricier vendor is premium, so the Source-A best vendor is NOT the cheapest:</p>
 *
 * <pre>
 *   product   base_price = 100.0000
 *   cheap     rating 3.0, ship 5d, markup  0% -&gt; unit price 100.00, score 0.80
 *   premium   rating 5.0, ship 1d, markup 10% -&gt; unit price 110.00, score 0.9455
 * </pre>
 *
 * <p>score = (1/normPrice)*0.60 + (rating/5)*0.30 + (1/avgShippingDays)*0.10, normalized by the
 * minimum eligible price (100.00). The premium vendor wins despite being more expensive.</p>
 *
 * <p>Boundary rule (AAP &sect;0.7.2): marketplace-service is self-contained for testing; there are no
 * cross-service ({@code users}/{@code orders}) imports and no HTTP stubs.</p>
 */
@SpringBootTest
@Testcontainers
class VendorSelectionServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("kitchensink");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /**
     * Seed the container from the three authoritative scripts IN ORDER (same approach as
     * {@code PricingServiceIT}). The full text of each file is executed as a SINGLE statement so the
     * dollar-quoted PL/pgSQL bodies in {@code 02_stored_procedures.sql} are not corrupted by a naive
     * {@code ;} splitter. Module-relative paths resolve because ITs run with CWD =
     * {@code marketplace-service/}.
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
    private VendorSelectionService vendorSelectionService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private VendorRepository vendorRepository;
    @Autowired
    private VendorInventoryRepository vendorInventoryRepository;

    // Generated ids for the divergent fixture, captured once and shared across tests.
    private static Long divergentProductId;
    private static Long cheapVendorId;
    private static Long premiumVendorId;

    /**
     * Inserts the divergent fixture exactly once (guarded by {@link #divergentProductId}). Uses the
     * real Spring Data repositories so IDENTITY ids are generated by Postgres and captured for the
     * assertions; the fixture is additive (new product + two new vendors) and does not disturb the
     * monotonic seed rows.
     */
    @BeforeEach
    void insertDivergentFixtureOnce() {
        if (divergentProductId != null) {
            return;
        }

        Product product = new Product();
        product.setName("Source-A Divergence Test Product");
        product.setSku("DIV-TEST-SKU-VSS-IT");
        product.setBasePrice(new BigDecimal("100.0000"));
        product.setWeightLbs(new BigDecimal("1.0000"));
        product.setCategory("test-fixtures");
        product = productRepository.save(product);
        divergentProductId = product.getId();

        Vendor cheap = new Vendor();
        cheap.setName("Cheap LowRated Vendor");
        cheap.setFulfillmentRating(new BigDecimal("3.0"));
        cheap.setAvgShippingDays(5);
        cheap.setContactEmail("cheap@example.test");
        cheap = vendorRepository.save(cheap);
        cheapVendorId = cheap.getId();

        Vendor premium = new Vendor();
        premium.setName("Premium HighRated Vendor");
        premium.setFulfillmentRating(new BigDecimal("5.0"));
        premium.setAvgShippingDays(1);
        premium.setContactEmail("premium@example.test");
        premium = vendorRepository.save(premium);
        premiumVendorId = premium.getId();

        // cheap: markup 0% -> unit price 100.00; premium: markup 10% -> unit price 110.00 (qty 1).
        VendorInventory cheapInv = new VendorInventory();
        cheapInv.setId(new VendorInventoryId(cheapVendorId, divergentProductId));
        cheapInv.setMarkupPercent(new BigDecimal("0.00"));
        cheapInv.setQuantityAvailable(500);
        vendorInventoryRepository.save(cheapInv);

        VendorInventory premiumInv = new VendorInventory();
        premiumInv.setId(new VendorInventoryId(premiumVendorId, divergentProductId));
        premiumInv.setMarkupPercent(new BigDecimal("10.00"));
        premiumInv.setQuantityAvailable(500);
        vendorInventoryRepository.save(premiumInv);
    }

    /**
     * selectBestVendor must pick the Source-A-maximized vendor (the premium, pricier vendor),
     * NOT the cheapest. This is the behavior orders-service consumes over HTTP via /best-vendor.
     */
    @Test
    void testSelectBestVendorPicksSourceAWinnerNotCheapest() {
        Long best = vendorSelectionService.selectBestVendor(divergentProductId, 1);
        assertNotNull(best, "A best vendor must be selected for an in-stock product");
        assertEquals(premiumVendorId, best,
                "Source-A maximization must select the premium vendor (score 0.9455), not the cheapest");
        assertNotEquals(cheapVendorId, best,
                "The cheapest vendor (score 0.80) must NOT be chosen as the Source-A best vendor");
    }

    /**
     * getVendorPricesForProduct must be Source-A score-ranked (best first), so the top entry is the
     * pricier premium vendor — demonstrating the list is NOT sorted by ascending unit price.
     */
    @Test
    void testVendorListIsSourceARankedNotPriceSorted() {
        List<VendorSelectionService.VendorPriceResult> ranked =
                vendorSelectionService.getVendorPricesForProduct(divergentProductId, 1);

        assertEquals(2, ranked.size(), "Exactly the two fixture vendors should be priced");

        VendorSelectionService.VendorPriceResult rank0 = ranked.get(0);
        VendorSelectionService.VendorPriceResult rank1 = ranked.get(1);

        assertEquals(premiumVendorId, rank0.vendorId,
                "Rank 0 must be the Source-A best (premium) vendor");
        assertEquals(cheapVendorId, rank1.vendorId,
                "Rank 1 must be the lower-scoring (cheap) vendor");

        assertTrue(rank0.unitPrice.compareTo(new BigDecimal("110.00")) == 0,
                "Premium vendor unit price must be 110.00");
        assertTrue(rank1.unitPrice.compareTo(new BigDecimal("100.00")) == 0,
                "Cheap vendor unit price must be 100.00");

        // The defining assertion: the best-ranked vendor is the MORE expensive one, proving the list
        // is Source-A score-ranked rather than cheapest-first (review C1).
        assertTrue(rank0.unitPrice.compareTo(rank1.unitPrice) > 0,
                "Source-A ranking must place the pricier premium vendor ABOVE the cheaper vendor");
    }

    /**
     * INFO-2 negative case: when a product's only vendor inventory is OUT OF STOCK
     * ({@code quantity_available = 0}), neither the eligible-vendor pass (which requires
     * {@code quantity_available >= quantity}) nor the cheapest-with-any-stock fallback (which
     * requires {@code quantity_available > 0}) can select it, so {@link VendorSelectionService#selectBestVendor(Long, int)}
     * must return {@code null}. This is the "no eligible vendor" signal that orders-service maps to
     * {@code NoEligibleVendorException}. Complements the positive maximization/ranking cases above.
     *
     * <p>The fixture is additive (a fresh product + vendor + zero-stock inventory row with a unique
     * SKU) and does not disturb the divergent or seeded rows.</p>
     */
    @Test
    void testSelectBestVendorReturnsNullWhenNoVendorInStock() {
        Product product = new Product();
        product.setName("Out Of Stock Test Product");
        product.setSku("OOS-TEST-SKU-VSS-IT");
        product.setBasePrice(new BigDecimal("50.0000"));
        product.setWeightLbs(new BigDecimal("1.0000"));
        product.setCategory("test-fixtures");
        product = productRepository.save(product);

        Vendor vendor = new Vendor();
        vendor.setName("Zero Stock Vendor");
        vendor.setFulfillmentRating(new BigDecimal("4.0"));
        vendor.setAvgShippingDays(2);
        vendor.setContactEmail("zerostock@example.test");
        vendor = vendorRepository.save(vendor);

        VendorInventory inventory = new VendorInventory();
        inventory.setId(new VendorInventoryId(vendor.getId(), product.getId()));
        inventory.setMarkupPercent(new BigDecimal("5.00"));
        inventory.setQuantityAvailable(0);
        vendorInventoryRepository.save(inventory);

        Long best = vendorSelectionService.selectBestVendor(product.getId(), 1);
        assertNull(best,
                "selectBestVendor must return null when the product's only vendor has zero stock");
    }
}
