package org.jboss.as.quickstarts.kitchensink.orders.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Service;

import org.jboss.as.quickstarts.kitchensink.orders.model.ShippingZone;
import org.jboss.as.quickstarts.kitchensink.orders.repository.ShippingZoneRepository;

/**
 * Faithful Java extraction of the calculate_shipping() PL/pgSQL stored procedure
 * (db/02_stored_procedures.sql, lines 151-191). The stored procedure is retained as reference
 * documentation only and is no longer invoked (zero native queries).
 */
@Service
public class ShippingService {

    // Business constants preserved EXACTLY from calculate_shipping (SQL L151-191).
    private static final BigDecimal SHIPPING_FLOOR      = new BigDecimal("5.99"); // GREATEST(5.99, ...)
    private static final BigDecimal EXPEDITE_MULTIPLIER = new BigDecimal("2.5");  // expedite -> * 2.5
    private static final BigDecimal DEFAULT_RATE        = new BigDecimal("1.50"); // no zone match -> 1.50

    // Constructor injection (single constructor -> no @Autowired required).
    private final ShippingZoneRepository shippingZoneRepository;

    public ShippingService(ShippingZoneRepository shippingZoneRepository) {
        this.shippingZoneRepository = shippingZoneRepository;
    }

    /**
     * Reproduces calculate_shipping (db/02_stored_procedures.sql L151-191):
     * zip prefix = first 3 digits (malformed -> 0); base rate from the first shipping_zones row
     * whose 3-digit prefix range contains the zip prefix (ORDER BY id), else default 1.50;
     * cost = GREATEST(5.99, baseRate * weight); expedite -> *2.5; round to 2 dp.
     */
    public BigDecimal calculateShipping(String destinationZip, BigDecimal totalWeightLbs, boolean expedite) {
        // v_zip_prefix := SUBSTRING(p_destination_zip FROM 1 FOR 3)::INTEGER, EXCEPTION -> 0 (SQL L160-165).
        int zipPrefix = parseZipPrefix(destinationZip);

        // SELECT base_rate_per_lb FROM shipping_zones
        //   WHERE prefix(zip_range_start) <= zipPrefix AND prefix(zip_range_end) >= zipPrefix
        //   ORDER BY id LIMIT 1 (SQL L168-174). Performed in Java over an id-ordered load.
        BigDecimal baseRate = DEFAULT_RATE; // IF NOT FOUND -> 1.50 (SQL L177-179).
        List<ShippingZone> zones = shippingZoneRepository.findAllByOrderByIdAsc();
        for (ShippingZone zone : zones) {
            int start = parseZipPrefix(zone.getZipRangeStart());
            int end = parseZipPrefix(zone.getZipRangeEnd());
            if (start <= zipPrefix && end >= zipPrefix) {
                baseRate = zone.getBaseRatePerLb();
                break; // ORDER BY id LIMIT 1 -> first match wins.
            }
        }

        // v_shipping_cost := GREATEST(5.99, v_base_rate * p_total_weight_lbs) (SQL L182).
        BigDecimal cost = baseRate.multiply(totalWeightLbs).max(SHIPPING_FLOOR);

        // IF p_expedite THEN v_shipping_cost := v_shipping_cost * 2.5 (SQL L185-187).
        if (expedite) {
            cost = cost.multiply(EXPEDITE_MULTIPLIER);
        }

        // RETURN ROUND(v_shipping_cost, 2) (SQL L190).
        return cost.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * First three characters of the ZIP parsed to an int; null/short/non-numeric -> 0
     * (mirrors the SQL SUBSTRING(...)::INTEGER guarded by EXCEPTION WHEN others -> 0).
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
