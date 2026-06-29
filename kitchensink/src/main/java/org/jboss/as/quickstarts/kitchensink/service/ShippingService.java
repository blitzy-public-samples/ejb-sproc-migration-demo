package org.jboss.as.quickstarts.kitchensink.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import org.jboss.as.quickstarts.kitchensink.data.ProductRepository;
import org.jboss.as.quickstarts.kitchensink.data.ShippingZoneRepository;
import org.jboss.as.quickstarts.kitchensink.model.Product;
import org.jboss.as.quickstarts.kitchensink.model.ShippingZone;

/**
 * ShippingService — embodies the {@code calculate_shipping} stored procedure
 * (db/02_stored_procedures.sql L151-191).
 *
 * <p>Migrated from Jakarta EE CDI (formerly {@code @ApplicationScoped} with an injected
 * {@code EntityManager} executing a native {@code "SELECT calculate_shipping(...)"} query) to a
 * Spring {@code @Service} using constructor injection. No EntityManager, no native query, and no
 * {@code jakarta.*} component dependencies remain — the procedure's logic now lives entirely in
 * Java.</p>
 *
 * <p>The ZIP-to-zone bracketing that the SQL performed with {@code SUBSTRING(...)::INTEGER} is done
 * here in Java so it stays portable across PostgreSQL and the test database. The repository exposes
 * the candidate zones ordered by id ({@code findAllByOrderByIdAsc()}); this service selects the
 * first (lowest-id) zone whose 3-digit start/end range brackets the ZIP prefix, mirroring the
 * procedure's {@code ORDER BY id LIMIT 1}.</p>
 */
@Service
public class ShippingService {

    /** Minimum shipping charge — mirrors the SQL {@code GREATEST(5.99, ...)} floor. */
    private static final BigDecimal MIN_SHIPPING = new BigDecimal("5.99");

    /** Per-pound rate used when no zone brackets the ZIP prefix (SQL fallback {@code 1.50}). */
    private static final BigDecimal DEFAULT_RATE = new BigDecimal("1.50");

    /** Expedited-shipping surcharge multiplier — mirrors the SQL {@code * 2.5}. */
    private static final BigDecimal EXPEDITE_MULT = new BigDecimal("2.5");

    private final ShippingZoneRepository shippingZoneRepository;
    private final ProductRepository productRepository;

    // Constructor injection replaces CDI @Inject field injection. A single constructor needs no
    // @Autowired. Both collaborators are Spring Data repositories — there is no EntityManager.
    public ShippingService(ShippingZoneRepository shippingZoneRepository,
                           ProductRepository productRepository) {
        this.shippingZoneRepository = shippingZoneRepository;
        this.productRepository = productRepository;
    }

    /**
     * Calculates the shipping cost for an order. This is the Java re-implementation of the
     * {@code calculate_shipping} stored procedure (no native query): resolve the destination zone's
     * per-pound rate from the ZIP prefix, apply the {@code GREATEST(5.99, rate * weight)} floor,
     * apply the expedite surcharge, then round to two decimals.
     *
     * @param destinationZip the destination ZIP code (only the first 3 characters are significant)
     * @param totalWeightLbs the total weight of all items; {@code null} is treated as zero
     * @param expedite       whether expedited shipping is requested
     * @return the shipping cost scaled to 2 decimals (the {@code shipping_cost} column is NUMERIC(8,2))
     */
    public BigDecimal calculateShipping(String destinationZip, BigDecimal totalWeightLbs, boolean expedite) {
        // ZIP prefix = first 3 chars parsed to int; fallback 0 on malformed/short input
        // (mirrors the SQL "EXCEPTION WHEN OTHERS THEN v_zip_prefix := 0" handler).
        int zipPrefix = parseZipPrefix(destinationZip);

        // Select the bracketing zone: the first (lowest-id) zone whose 3-digit start/end range
        // brackets the prefix (mirrors "ORDER BY id LIMIT 1"). If none matches, keep the 1.50 fallback.
        BigDecimal baseRate = DEFAULT_RATE;
        List<ShippingZone> zones = shippingZoneRepository.findAllByOrderByIdAsc();
        for (ShippingZone zone : zones) {
            int start = parseZipPrefix(zone.getZipRangeStart());
            int end = parseZipPrefix(zone.getZipRangeEnd());
            if (start <= zipPrefix && end >= zipPrefix) {
                // SEMANTIC PARITY with calculate_shipping (db/02_stored_procedures.sql): the SQL reads
                // the matched zone's base_rate_per_lb INTO v_base_rate and then applies
                // "IF v_base_rate IS NULL THEN v_base_rate := 1.50", so the 1.50 default covers BOTH the
                // no-zone-matched case AND a matched zone whose rate is NULL. shipping_zones.base_rate_per_lb
                // is NULLABLE in db/01_schema.sql (NUMERIC(6,4), no NOT NULL constraint), so a matched zone
                // may carry a null rate. Only overwrite the DEFAULT_RATE (1.50) when the matched zone's rate
                // is non-null; a null matched rate keeps the 1.50 fallback exactly as the procedure does,
                // instead of assigning null and throwing a NullPointerException at the multiply below.
                BigDecimal zoneRate = zone.getBaseRatePerLb();
                if (zoneRate != null) {
                    baseRate = zoneRate;
                }
                // First-match break preserved (mirrors "ORDER BY id LIMIT 1"): selection stops at the
                // lowest-id bracketing zone whether or not its rate is null.
                break;
            }
        }

        // COALESCE-style guard: a null total weight behaves like 0. (The SQL GREATEST ignores the
        // resulting NULL and yields the 5.99 floor, which equals max(5.99, rate * 0) computed here.)
        BigDecimal weight = (totalWeightLbs == null) ? BigDecimal.ZERO : totalWeightLbs;

        // GREATEST(5.99, base_rate * weight): the floor is applied BEFORE the expedite surcharge.
        BigDecimal cost = baseRate.multiply(weight).max(MIN_SHIPPING);

        // 2.5x surcharge for expedited shipping — applied after the floor and before rounding.
        if (expedite) {
            cost = cost.multiply(EXPEDITE_MULT);
        }

        // ROUND(..., 2): the shipping_cost column is NUMERIC(8,2).
        return cost.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Estimates shipping cost from a map of product IDs to quantities: sums each product's weight
     * ({@code COALESCE(weight_lbs, 0) * quantity}) and delegates to {@link #calculateShipping}.
     *
     * <p>Retained as part of the public API for caller compatibility. Adapted for the Spring Data
     * migration: {@code productRepository.findById(...)} now returns an {@code Optional<Product>}, so
     * a missing product — or a present product whose {@code weightLbs} is {@code null} — contributes
     * zero weight.</p>
     *
     * @param productQuantities map of product ID to quantity
     * @param destinationZip    the destination ZIP code
     * @param expedite          whether expedited shipping is requested
     * @return the estimated shipping cost
     */
    public BigDecimal estimateShipping(Map<Long, Integer> productQuantities, String destinationZip, boolean expedite) {
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : productQuantities.entrySet()) {
            // COALESCE(weight_lbs, 0) * quantity. findById returns Optional<Product>; a missing
            // product maps to ZERO, and a present product with a null weight is guarded to ZERO too.
            BigDecimal unitWeight = productRepository.findById(entry.getKey())
                .map(Product::getWeightLbs)
                .orElse(BigDecimal.ZERO);
            if (unitWeight == null) {
                unitWeight = BigDecimal.ZERO;
            }
            totalWeight = totalWeight.add(unitWeight.multiply(BigDecimal.valueOf(entry.getValue())));
        }
        return calculateShipping(destinationZip, totalWeight, expedite);
    }

    /**
     * Parses the first three characters of a ZIP / zone-boundary string into an int, returning 0 on
     * any malformed or too-short input. This centralizes the SQL's {@code SUBSTRING(...)::INTEGER}
     * conversion and its {@code EXCEPTION WHEN OTHERS THEN 0} fallback so the destination ZIP and the
     * zone bounds use identical prefix semantics.
     */
    private int parseZipPrefix(String zip) {
        if (zip == null || zip.length() < 3) {
            return 0;
        }
        try {
            return Integer.parseInt(zip.substring(0, 3));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
