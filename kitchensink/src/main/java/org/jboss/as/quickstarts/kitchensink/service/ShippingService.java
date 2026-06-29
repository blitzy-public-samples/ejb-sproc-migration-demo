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
 * ShippingService — pure-Java re-implementation of the {@code calculate_shipping(destination_zip,
 * total_weight_lbs, expedite)} PL/pgSQL stored procedure.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): formerly a CDI
 * {@code @ApplicationScoped} bean delegating to a native {@code SELECT calculate_shipping(...)}
 * query via {@code EntityManager}. It is now a Spring {@code @Service} that resolves the shipping
 * zone and computes the cost in Java using injected Spring Data repositories.</p>
 *
 * <p>Logic preserved exactly: derive a 3-digit ZIP prefix (fallback 0 on malformed input), select
 * the first zone (ordered by id) whose 3-digit start/end range brackets the prefix, fall back to a
 * base rate of 1.50 if no zone matches, compute {@code GREATEST(5.99, base_rate * weight)}, apply a
 * 2.5x surcharge when expedited, and round to 2 decimal places.</p>
 */
@Service
public class ShippingService {

    /** Minimum shipping charge floor (GREATEST(5.99, ...)). */
    private static final BigDecimal MIN_SHIPPING = new BigDecimal("5.99");
    /** Default per-pound rate when no shipping zone matches the destination ZIP. */
    private static final BigDecimal DEFAULT_RATE = new BigDecimal("1.50");
    /** Multiplier applied for expedited shipping. */
    private static final BigDecimal EXPEDITE_MULTIPLIER = new BigDecimal("2.5");

    private final ShippingZoneRepository shippingZoneRepository;
    private final ProductRepository productRepository;

    public ShippingService(ShippingZoneRepository shippingZoneRepository,
                           ProductRepository productRepository) {
        this.shippingZoneRepository = shippingZoneRepository;
        this.productRepository = productRepository;
    }

    /**
     * Calculates the shipping cost for an order, mirroring {@code calculate_shipping}.
     *
     * @param destinationZip   the destination ZIP code
     * @param totalWeightLbs   the total weight of all items
     * @param expedite         whether expedited shipping is requested
     * @return                 the shipping cost (scale 2)
     */
    public BigDecimal calculateShipping(String destinationZip, BigDecimal totalWeightLbs, boolean expedite) {
        int zipPrefix = parseZipPrefix(destinationZip);

        // Select the first zone (ordered by id) whose 3-digit start/end range brackets the prefix.
        BigDecimal baseRate = null;
        for (ShippingZone zone : shippingZoneRepository.findAllByOrderByIdAsc()) {
            Integer start = parsePrefix(zone.getZipRangeStart());
            Integer end = parsePrefix(zone.getZipRangeEnd());
            if (start != null && end != null && start <= zipPrefix && end >= zipPrefix) {
                baseRate = zone.getBaseRatePerLb();
                break;
            }
        }
        if (baseRate == null) {
            baseRate = DEFAULT_RATE;
        }

        BigDecimal weight = (totalWeightLbs != null) ? totalWeightLbs : BigDecimal.ZERO;
        BigDecimal computed = baseRate.multiply(weight);

        // GREATEST(5.99, base_rate * weight)
        BigDecimal cost = (computed.compareTo(MIN_SHIPPING) > 0) ? computed : MIN_SHIPPING;

        if (expedite) {
            cost = cost.multiply(EXPEDITE_MULTIPLIER);
        }
        return cost.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Estimates shipping cost given a map of product IDs to quantities. Fetches each product's
     * weight, sums the total, then delegates to {@link #calculateShipping}.
     *
     * @param productQuantities  map of product ID to quantity
     * @param destinationZip     the destination ZIP code
     * @param expedite           whether expedited shipping is requested
     * @return                   the estimated shipping cost
     */
    public BigDecimal estimateShipping(Map<Long, Integer> productQuantities, String destinationZip, boolean expedite) {
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : productQuantities.entrySet()) {
            Product product = productRepository.findById(entry.getKey()).orElse(null);
            if (product != null && product.getWeightLbs() != null) {
                BigDecimal itemWeight = product.getWeightLbs()
                    .multiply(BigDecimal.valueOf(entry.getValue()));
                totalWeight = totalWeight.add(itemWeight);
            }
        }
        return calculateShipping(destinationZip, totalWeight, expedite);
    }

    /**
     * Parses the leading 3-digit ZIP prefix from a destination ZIP, mirroring the stored
     * procedure's {@code SUBSTRING(zip FROM 1 FOR 3)::INTEGER} with a fallback of 0 on malformed
     * input.
     */
    private int parseZipPrefix(String zip) {
        Integer prefix = parsePrefix(zip);
        return (prefix != null) ? prefix : 0;
    }

    /**
     * Parses the first three characters of a ZIP string as an integer (or the whole string if
     * shorter than three characters). Returns {@code null} when the value cannot be parsed.
     */
    private Integer parsePrefix(String value) {
        if (value == null) {
            return null;
        }
        String prefix = (value.length() >= 3) ? value.substring(0, 3) : value;
        try {
            return Integer.parseInt(prefix.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
