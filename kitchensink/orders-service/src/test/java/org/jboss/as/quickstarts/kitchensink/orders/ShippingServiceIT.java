package org.jboss.as.quickstarts.kitchensink.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.jboss.as.quickstarts.kitchensink.orders.client.MarketplaceClient;
import org.jboss.as.quickstarts.kitchensink.orders.client.UsersClient;
import org.jboss.as.quickstarts.kitchensink.orders.model.ShippingZone;
import org.jboss.as.quickstarts.kitchensink.orders.repository.ShippingZoneRepository;
import org.jboss.as.quickstarts.kitchensink.orders.service.ShippingService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link ShippingService}, the pure-Java reimplementation of the
 * {@code calculate_shipping} PL/pgSQL stored procedure (db/02_stored_procedures.sql L151-191;
 * reference only, never invoked at runtime).
 *
 * <p>Consolidates the shipping-computation coverage that was previously absent from the suite
 * (QA checkpoint F6):</p>
 * <ul>
 *   <li><b>Expedited-shipping {@code &times;2.5} multiplier</b> — the whole point of this class.
 *       It is exercised BOTH when the {@code $5.99} floor is active (proving the multiplier is
 *       applied AFTER the floor, AAP &sect;0.6.1) and when {@code rate &times; weight} is above the
 *       floor.</li>
 *   <li><b>Null-zone {@code $1.50/lb} fallback</b> — reached with a ZIP whose 3-digit prefix
 *       matches no configured zone.</li>
 *   <li><b>Multi-zone lookup</b> — different ZIP prefixes resolve to different per-pound rates.</li>
 *   <li><b>The {@code GREATEST(5.99, rate &times; weight)} floor</b> on a light, non-expedited
 *       shipment.</li>
 * </ul>
 *
 * <p><b>Why this class controls its own {@code shipping_zones} fixture (and does NOT load
 * {@code db/03_seed_data.sql}):</b> the authoritative seed defines five contiguous zones spanning
 * ZIP prefixes {@code 000}&ndash;{@code 999} with NO gap, which makes the {@code calculate_shipping}
 * null-zone fallback branch <i>unreachable</i> via any valid ZIP. To assert the fallback (and to keep
 * the fixture minimal and self-describing) this IT seeds only the schema and the stored procedures
 * (for parity provenance) and then inserts exactly two zones — Southeast ({@code 20000}&ndash;
 * {@code 39999} @ {@code 0.95/lb}) and West_Pacific ({@code 80000}&ndash;{@code 99999} @
 * {@code 1.25/lb}) — deliberately leaving the {@code 40000}&ndash;{@code 79999} prefix band
 * uncovered so a ZIP such as {@code 50000} exercises the fallback. The two seeded rates mirror the
 * authoritative seed's Southeast/West_Pacific rates, so the asserted values are faithful.</p>
 *
 * <p>The class is intentionally NOT {@code @Transactional}: the fixture rows are committed by the
 * repository so the {@code ShippingZoneRepository} native zone lookup (a plain {@code SELECT}) sees
 * them. Boundary rule (AAP &sect;0.7.2): {@link ShippingService} has no cross-service dependency; the
 * {@link MarketplaceClient}/{@link UsersClient} gateways are mocked ONLY so the full orders-service
 * application context loads identically to the sibling ITs.</p>
 */
@SpringBootTest
@Testcontainers
class ShippingServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("kitchensink");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /**
     * Seed the schema (01) and the stored procedures (02) — the latter for parity provenance only;
     * the application never calls them. {@code 03_seed_data.sql} is intentionally NOT loaded (see the
     * class javadoc). Each file is executed as a SINGLE statement (never split on {@code ;}) because
     * {@code 02_stored_procedures.sql} contains dollar-quoted ($$) PL/pgSQL bodies. Module-relative
     * {@code ../db/...} resolves with CWD = {@code orders-service/}.
     */
    @BeforeAll
    static void seedDatabase() throws Exception {
        String[] scripts = {"../db/01_schema.sql", "../db/02_stored_procedures.sql"};
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            for (String script : scripts) {
                String sql = Files.readString(Path.of(script), StandardCharsets.UTF_8);
                statement.execute(sql);
            }
        }
    }

    @Autowired
    private ShippingService shippingService;

    @Autowired
    private ShippingZoneRepository shippingZoneRepository;

    // Mocked purely so the full orders-service context loads (OrderService/DiscountService require
    // these gateway beans). ShippingService itself never touches either client.
    @MockitoBean
    private MarketplaceClient marketplaceClient;

    @MockitoBean
    private UsersClient usersClient;

    /**
     * Insert the controlled two-zone fixture exactly once (guarded by the row count, since the
     * per-class container is shared across the test methods and this class is not
     * {@code @Transactional}). A deliberate coverage gap at prefixes {@code 400}&ndash;{@code 799} is
     * left uncovered so the null-zone fallback can be asserted.
     */
    @BeforeEach
    void insertControlledZonesOnce() {
        if (shippingZoneRepository.count() > 0) {
            return;
        }
        shippingZoneRepository.save(zone("Southeast", "20000", "39999", "0.9500", 2, 4));
        shippingZoneRepository.save(zone("West_Pacific", "80000", "99999", "1.2500", 3, 7));
    }

    private static ShippingZone zone(String name, String start, String end, String rate,
                                     int minDays, int maxDays) {
        ShippingZone z = new ShippingZone();
        z.setZoneName(name);
        z.setZipRangeStart(start);
        z.setZipRangeEnd(end);
        z.setBaseRatePerLb(new BigDecimal(rate));
        z.setMinDays(minDays);
        z.setMaxDays(maxDays);
        return z;
    }

    /**
     * Baseline: a light, non-expedited shipment falls to the {@code GREATEST(5.99, ...)} floor.
     * ZIP 27601 -> Southeast @ 0.95/lb; 0.95 * 1.00 = 0.95 < 5.99 -> 5.99.
     */
    @Test
    void testNonExpeditedFloorApplies() {
        BigDecimal cost = shippingService.calculateShipping("27601", new BigDecimal("1.00"), false);
        assertEquals(0, new BigDecimal("5.99").compareTo(cost),
                "A light non-expedited shipment must fall to the $5.99 minimum-shipping floor");
    }

    /**
     * MINOR-2 (floor ordering): the expedite x2.5 surcharge is applied AFTER the $5.99 floor.
     * ZIP 27601 -> 0.95/lb; 0.95 * 1.00 = 0.95 -> floor 5.99 -> x2.5 = 14.975 -> 14.98.
     *
     * <p>This is the decisive ordering assertion: had the multiplier been applied BEFORE the floor,
     * the result would be {@code GREATEST(5.99, 0.95 * 2.5 * 1.00) = 5.99}, not 14.98. Asserting
     * 14.98 therefore proves the floor is taken first and the x2.5 second.</p>
     */
    @Test
    void testExpediteMultiplierAppliedToFloor() {
        BigDecimal cost = shippingService.calculateShipping("27601", new BigDecimal("1.00"), true);
        assertEquals(0, new BigDecimal("14.98").compareTo(cost),
                "Expedited light shipment must be the $5.99 floor x2.5 = 14.98 (multiplier applied "
                        + "AFTER the floor)");
    }

    /**
     * MINOR-2 (above the floor): when {@code rate * weight} exceeds the floor, the expedite surcharge
     * multiplies that value. ZIP 27601 -> 0.95/lb; 0.95 * 15.00 = 14.25 (> 5.99); non-expedited =
     * 14.25, expedited = 14.25 x2.5 = 35.625 -> 35.63.
     */
    @Test
    void testExpediteMultiplierAppliedAboveFloor() {
        BigDecimal weight = new BigDecimal("15.00");
        BigDecimal base = shippingService.calculateShipping("27601", weight, false);
        BigDecimal expedited = shippingService.calculateShipping("27601", weight, true);

        assertEquals(0, new BigDecimal("14.25").compareTo(base),
                "Non-expedited above-floor shipping must be 0.95/lb x 15.00 lb = 14.25");
        assertEquals(0, new BigDecimal("35.63").compareTo(expedited),
                "Expedited above-floor shipping must be 14.25 x2.5 = 35.63");
        assertEquals(0, base.multiply(new BigDecimal("2.5")).setScale(2, java.math.RoundingMode.HALF_UP)
                        .compareTo(expedited),
                "Expedited cost must equal the non-expedited cost x2.5 (rounded to 2 dp)");
    }

    /**
     * INFO-4 (multi-zone lookup): different ZIP prefixes resolve to different per-pound rates.
     * ZIP 27601 -> Southeast @ 0.95/lb (0.95 * 10 = 9.50); ZIP 90210 -> West_Pacific @ 1.25/lb
     * (1.25 * 10 = 12.50). Both are above the floor so the distinct zone rates are directly visible.
     */
    @Test
    void testMultiZoneLookupSelectsCorrectRate() {
        BigDecimal southeast = shippingService.calculateShipping("27601", new BigDecimal("10.00"), false);
        BigDecimal westPacific = shippingService.calculateShipping("90210", new BigDecimal("10.00"), false);

        assertEquals(0, new BigDecimal("9.50").compareTo(southeast),
                "ZIP 27601 must resolve to the Southeast zone @ 0.95/lb -> 9.50 for 10 lb");
        assertEquals(0, new BigDecimal("12.50").compareTo(westPacific),
                "ZIP 90210 must resolve to the West_Pacific zone @ 1.25/lb -> 12.50 for 10 lb");
    }

    /**
     * INFO-4 (null-zone fallback): a ZIP whose prefix (500) matches no configured zone falls back to
     * the $1.50/lb default rate. 1.50 * 10.00 = 15.00 (> 5.99). The 15.00 result is distinct from
     * every seeded zone rate, so it unambiguously proves the fallback branch was taken.
     */
    @Test
    void testNullZoneFallbackRate() {
        BigDecimal cost = shippingService.calculateShipping("50000", new BigDecimal("10.00"), false);
        assertEquals(0, new BigDecimal("15.00").compareTo(cost),
                "A ZIP matching no zone must use the $1.50/lb fallback -> 1.50 x 10.00 = 15.00");
    }
}
