package org.jboss.as.quickstarts.kitchensink.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.jboss.as.quickstarts.kitchensink.data.ProductRepository;
import org.jboss.as.quickstarts.kitchensink.data.ShippingZoneRepository;
import org.jboss.as.quickstarts.kitchensink.model.ShippingZone;
import org.jboss.as.quickstarts.kitchensink.service.ShippingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link ShippingService} focused on the {@code calculate_shipping} null matched-zone
 * rate fallback (regression test for the MAJOR semantic-parity finding).
 *
 * <p>This is a pure JUnit 5 + Mockito unit test (no Spring context, runs under Surefire as a
 * {@code *Test}) rather than a {@code @SpringBootTest} integration test, because the case under test
 * is <strong>unreachable through the seeded test database</strong>: the five seeded
 * {@code shipping_zones} (db/03_seed_data.sql) cover the full 000-999 three-digit prefix space with
 * non-null {@code base_rate_per_lb} values and lower ids, so no real query can ever return a matched
 * zone whose rate is null. Stubbing {@link ShippingZoneRepository} lets the test exercise the exact
 * branch the stored procedure handles via {@code IF v_base_rate IS NULL THEN v_base_rate := 1.50}.</p>
 *
 * <p>The {@code shipping_zones.base_rate_per_lb} column is {@code NUMERIC(6,4)} and NULLABLE in
 * db/01_schema.sql, so a matched zone genuinely may carry a null rate; before the fix that produced a
 * {@link NullPointerException}, whereas the stored procedure produced the 1.50 fallback.</p>
 */
@ExtendWith(MockitoExtension.class)
public class ShippingServiceTest {

    @Mock
    private ShippingZoneRepository shippingZoneRepository;

    // ProductRepository is a constructor collaborator of ShippingService but is not exercised by
    // calculateShipping (only estimateShipping uses it), so it is mocked and left unstubbed.
    @Mock
    private ProductRepository productRepository;

    private ShippingService newService() {
        return new ShippingService(shippingZoneRepository, productRepository);
    }

    private static ShippingZone zone(String start, String end, BigDecimal rate) {
        ShippingZone z = new ShippingZone();
        z.setZipRangeStart(start);
        z.setZipRangeEnd(end);
        z.setBaseRatePerLb(rate);
        return z;
    }

    /**
     * F1 regression: a matched zone whose {@code base_rate_per_lb} is null must fall back to the
     * 1.50 default (mirroring the SQL {@code IF v_base_rate IS NULL THEN 1.50}) rather than throwing
     * an NPE. With weight 10: max(5.99, 1.50 * 10) = 15.00.
     */
    @Test
    public void testMatchedZoneWithNullRateFallsBackToDefault() {
        when(shippingZoneRepository.findAllByOrderByIdAsc())
            .thenReturn(List.of(zone("00000", "99999", null)));

        assertEquals(new BigDecimal("15.00"),
            newService().calculateShipping("12345", new BigDecimal("10"), false),
            "Matched zone with null rate must use the 1.50 default: max(5.99, 1.50*10) = 15.00");
    }

    /**
     * F1 regression: the 5.99 floor still applies when the null-rate fallback yields a tiny cost.
     * With weight 1: max(5.99, 1.50 * 1) = 5.99.
     */
    @Test
    public void testMatchedZoneWithNullRateAppliesFloor() {
        when(shippingZoneRepository.findAllByOrderByIdAsc())
            .thenReturn(List.of(zone("00000", "99999", null)));

        assertEquals(new BigDecimal("5.99"),
            newService().calculateShipping("12345", new BigDecimal("1"), false),
            "Matched zone with null rate and light weight must floor to 5.99");
    }

    /**
     * F1 regression: the 2.5x expedite multiplier still applies after the null-rate fallback and
     * floor. With weight 10 expedited: max(5.99, 1.50 * 10) * 2.5 = 15.00 * 2.5 = 37.50.
     */
    @Test
    public void testMatchedZoneWithNullRateAppliesExpediteMultiplier() {
        when(shippingZoneRepository.findAllByOrderByIdAsc())
            .thenReturn(List.of(zone("00000", "99999", null)));

        assertEquals(new BigDecimal("37.50"),
            newService().calculateShipping("12345", new BigDecimal("10"), true),
            "Matched zone with null rate + expedite must yield 15.00 * 2.5 = 37.50");
    }

    /**
     * Guard against over-correction: when the matched zone HAS a real rate, that rate must still be
     * used (the null check must not discard valid rates). With rate 0.85 and weight 10:
     * max(5.99, 0.85 * 10) = 8.50.
     */
    @Test
    public void testMatchedZoneWithRateUsesZoneRate() {
        when(shippingZoneRepository.findAllByOrderByIdAsc())
            .thenReturn(List.of(zone("00000", "99999", new BigDecimal("0.8500"))));

        assertEquals(new BigDecimal("8.50"),
            newService().calculateShipping("12345", new BigDecimal("10"), false),
            "Matched zone with a non-null rate must use the zone rate: max(5.99, 0.85*10) = 8.50");
    }

    /**
     * Existing behavior preserved: when no zone brackets the ZIP prefix, the 1.50 default applies
     * (this path already worked before the fix). Prefix 123 is not within 900-999, so no match.
     */
    @Test
    public void testNoMatchingZoneFallsBackToDefault() {
        when(shippingZoneRepository.findAllByOrderByIdAsc())
            .thenReturn(List.of(zone("90000", "99999", new BigDecimal("1.2500"))));

        assertEquals(new BigDecimal("15.00"),
            newService().calculateShipping("12345", new BigDecimal("10"), false),
            "No bracketing zone must use the 1.50 default: max(5.99, 1.50*10) = 15.00");
    }

    /**
     * First-match-by-id parity: a null-rate FIRST bracketing zone must NOT cause a forward scan to a
     * later non-null zone. The stored procedure does {@code ORDER BY id LIMIT 1} — exactly one row is
     * considered, then the null-to-1.50 fallback is applied. So the later 0.85 zone must be ignored.
     */
    @Test
    public void testFirstMatchNullRateDoesNotScanToLaterZone() {
        when(shippingZoneRepository.findAllByOrderByIdAsc())
            .thenReturn(List.of(
                zone("00000", "99999", null),                        // first (lowest-id) match, null rate
                zone("00000", "99999", new BigDecimal("0.8500"))));  // later zone, must be ignored

        assertEquals(new BigDecimal("15.00"),
            newService().calculateShipping("12345", new BigDecimal("10"), false),
            "First bracketing zone (null rate) wins -> 1.50; later zones ignored (ORDER BY id LIMIT 1)");
    }
}
