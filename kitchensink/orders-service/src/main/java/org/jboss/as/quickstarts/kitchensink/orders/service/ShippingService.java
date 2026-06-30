package org.jboss.as.quickstarts.kitchensink.orders.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.jboss.as.quickstarts.kitchensink.orders.model.ShippingZone;
import org.jboss.as.quickstarts.kitchensink.orders.repository.ShippingZoneRepository;
import org.springframework.stereotype.Service;

/**
 * Calculates shipping cost. Pure-Java reimplementation of the {@code calculate_shipping}
 * PL/pgSQL stored procedure (db/02_stored_procedures.sql L151-191); no native query is
 * ever executed.
 *
 * <p>This is the foundational service of the orders-service {@code service/} layer: it has
 * no dependency on any sibling service and never reaches across the cross-domain boundary
 * (AAP §0.7.2). Its only collaborator is the {@link ShippingZoneRepository}, injected via
 * the constructor; the legacy {@code EntityManager}, the native {@code SELECT
 * calculate_shipping(...)} call, and the {@code ProductRepository}-based weight summation
 * of the monolith are intentionally dropped.</p>
 *
 * <p>All monetary arithmetic uses {@link BigDecimal} with {@link RoundingMode#HALF_UP},
 * matching PostgreSQL's {@code ROUND(x, 2)} half-up behaviour for positive values, so the
 * results are bit-for-bit faithful to the original procedure.</p>
 */
@Service
public class ShippingService {

    /** Minimum shipping charge — the {@code GREATEST(5.99, ...)} floor in the procedure. */
    private static final BigDecimal MIN_SHIPPING = new BigDecimal("5.99");

    /** Expedited-shipping surcharge multiplier, applied AFTER the {@link #MIN_SHIPPING} floor. */
    private static final BigDecimal EXPEDITE_MULTIPLIER = new BigDecimal("2.5");

    /** Per-pound fallback rate used when no shipping zone matches the destination ZIP. */
    private static final BigDecimal FALLBACK_RATE_PER_LB = new BigDecimal("1.50");

    private final ShippingZoneRepository shippingZoneRepository;

    public ShippingService(ShippingZoneRepository shippingZoneRepository) {
        this.shippingZoneRepository = shippingZoneRepository;
    }

    /**
     * Reimplements {@code calculate_shipping(p_destination_zip, p_total_weight_lbs, p_expedite)}.
     *
     * <p>Logic, faithful to the procedure: derive the 3-digit ZIP prefix; look up the
     * matching {@link ShippingZone}; resolve the per-pound base rate (falling back to
     * {@code $1.50/lb} when no zone matches); apply the {@code GREATEST(5.99, base_rate ×
     * weight)} floor FIRST; multiply by {@code 2.5} for expedited shipping AFTER the floor;
     * round the result to two decimals, half-up.</p>
     *
     * @param destinationZip the destination ZIP code (may be {@code null} or malformed)
     * @param totalWeightLbs the total order weight in pounds (treated as {@code 0} when {@code null})
     * @param expedite       whether expedited shipping is requested
     * @return the shipping cost, rounded to two decimals (half-up)
     */
    public BigDecimal calculateShipping(String destinationZip, BigDecimal totalWeightLbs, boolean expedite) {
        int zipPrefix = parseZipPrefix(destinationZip);

        ShippingZone zone = shippingZoneRepository.findZoneByZipPrefix(zipPrefix);
        BigDecimal baseRate = (zone != null && zone.getBaseRatePerLb() != null)
                ? zone.getBaseRatePerLb()
                : FALLBACK_RATE_PER_LB;

        BigDecimal weight = (totalWeightLbs != null) ? totalWeightLbs : BigDecimal.ZERO;

        // GREATEST(5.99, base_rate * weight) -- the 5.99 floor is applied FIRST.
        BigDecimal shippingCost = baseRate.multiply(weight).max(MIN_SHIPPING);

        // 2.5x expedite surcharge applied AFTER the floor.
        if (expedite) {
            shippingCost = shippingCost.multiply(EXPEDITE_MULTIPLIER);
        }

        return shippingCost.setScale(2, RoundingMode.HALF_UP);
    }

    private int parseZipPrefix(String destinationZip) {
        // SUBSTRING(zip FROM 1 FOR 3)::INTEGER; malformed/short/null ZIP -> 0
        // (proc traps EXCEPTION WHEN OTHERS -> 0).
        if (destinationZip == null || destinationZip.length() < 3) {
            return 0;
        }
        try {
            return Integer.parseInt(destinationZip.substring(0, 3));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
