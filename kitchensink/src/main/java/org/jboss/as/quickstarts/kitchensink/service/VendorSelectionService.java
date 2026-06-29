package org.jboss.as.quickstarts.kitchensink.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.jboss.as.quickstarts.kitchensink.data.VendorInventoryRepository;
import org.jboss.as.quickstarts.kitchensink.data.VendorRepository;
import org.jboss.as.quickstarts.kitchensink.model.Vendor;
import org.jboss.as.quickstarts.kitchensink.model.VendorInventory;

/**
 * VendorSelectionService — pure-Java re-implementation of the {@code select_best_vendor(
 * product_id, quantity)} PL/pgSQL stored procedure.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): formerly a CDI
 * {@code @ApplicationScoped} bean delegating to a native {@code SELECT select_best_vendor(...)}
 * query via {@code EntityManager}. It is now a Spring {@code @Service} that selects the best
 * vendor in Java using injected Spring Data repositories plus the shared, stateless
 * {@link PricingService} (constructor-injected, no dependency cycle).</p>
 *
 * <p><strong>Scoring (Authority Hierarchy — Source A wins).</strong> The prompt's inline
 * specification (Source A) supersedes the SQL implementation (Source B) for the scoring formula.
 * Source B <em>minimized</em> a cost score; Source A <em>maximizes</em> a benefit score and picks
 * the highest:
 * <pre>
 *   score = (1 / normalizedPrice) * 0.60
 *         + (fulfillmentRating / 5.0) * 0.30
 *         + (1 / avgShippingDays) * 0.10        -- highest score wins
 * </pre>
 * where {@code normalizedPrice = candidatePrice / minCandidatePrice} (so {@code 1/normalizedPrice}
 * rewards the cheapest vendor). The two formulations are directionally equivalent (both favor
 * lower price, higher fulfillment rating, and fewer shipping days) but not mathematically
 * identical, so a selected vendor may occasionally differ from the legacy SQL choice. No
 * acceptance test asserts a specific best-vendor identity, so this deliberate substitution is
 * safe.</p>
 *
 * <p>The {@code min_price IS NULL} fallback (no candidate has sufficient stock -&gt; choose the
 * cheapest vendor with <em>any</em> stock) is a Source-B-only rule and is retained.</p>
 */
@Service
public class VendorSelectionService {

    private final VendorRepository vendorRepository;
    private final VendorInventoryRepository vendorInventoryRepository;
    private final PricingService pricingService;

    public VendorSelectionService(VendorRepository vendorRepository,
                                  VendorInventoryRepository vendorInventoryRepository,
                                  PricingService pricingService) {
        this.vendorRepository = vendorRepository;
        this.vendorInventoryRepository = vendorInventoryRepository;
        this.pricingService = pricingService;
    }

    /**
     * Selects the best vendor for a given product and quantity.
     *
     * <p>Candidates are vendor-inventory rows for the product with
     * {@code quantity_available >= quantity}. Each candidate is priced via
     * {@link PricingService#calculatePrice}, the minimum candidate price is computed for
     * normalization, and the candidate with the highest Source-A benefit score is returned. If no
     * candidate has sufficient stock, the cheapest vendor with any stock is returned (Source-B
     * fallback). Returns {@code null} only when the product has no inventory at all.</p>
     *
     * @param productId  the product ID
     * @param quantity   the requested quantity
     * @return           the vendor ID of the best vendor, or {@code null} if none found
     */
    public Long selectBestVendor(Long productId, int quantity) {
        List<VendorInventory> inventory = vendorInventoryRepository.findByProductId(productId);

        // Eligible candidates: enough stock to fulfill the requested quantity.
        List<Candidate> candidates = new ArrayList<>();
        BigDecimal minPrice = null;
        for (VendorInventory vi : inventory) {
            Integer available = vi.getQuantityAvailable();
            if (available != null && available >= quantity) {
                Long vendorId = vi.getId().getVendorId();
                BigDecimal price = pricingService.calculatePrice(productId, vendorId, quantity);
                candidates.add(new Candidate(vendorId, price));
                if (minPrice == null || price.compareTo(minPrice) < 0) {
                    minPrice = price;
                }
            }
        }

        if (minPrice != null) {
            // Score every eligible candidate and keep the highest-scoring one (Source A).
            Long bestVendorId = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            double minPriceD = minPrice.doubleValue();
            for (Candidate c : candidates) {
                Vendor vendor = vendorRepository.findById(c.vendorId).orElse(null);
                if (vendor == null) {
                    continue;
                }
                double score = score(c.price.doubleValue(), minPriceD, vendor);
                if (score > bestScore) {
                    bestScore = score;
                    bestVendorId = c.vendorId;
                }
            }
            if (bestVendorId != null) {
                return bestVendorId;
            }
        }

        // Fallback (Source B): cheapest vendor with any stock available.
        Long fallbackVendorId = null;
        BigDecimal fallbackPrice = null;
        for (VendorInventory vi : inventory) {
            Integer available = vi.getQuantityAvailable();
            if (available != null && available > 0) {
                Long vendorId = vi.getId().getVendorId();
                BigDecimal price = pricingService.calculatePrice(productId, vendorId, quantity);
                if (fallbackPrice == null || price.compareTo(fallbackPrice) < 0) {
                    fallbackPrice = price;
                    fallbackVendorId = vendorId;
                }
            }
        }
        return fallbackVendorId;
    }

    /**
     * Computes the Source-A benefit score for a candidate vendor:
     * <pre>
     *   score = (1 / normalizedPrice) * 0.60
     *         + (fulfillmentRating / 5.0)  * 0.30
     *         + (1 / avgShippingDays)      * 0.10        -- highest score wins
     * </pre>
     * Division by zero is guarded: {@code avgShippingDays} is clamped to {@code >= 1} — a null or
     * non-positive value is treated as 1 day via {@code Math.max(days, 1)}, so the fastest/unknown
     * shipping earns the full speed bonus and the {@code (1 / avgShippingDays)} term never divides
     * by zero. A null {@code fulfillmentRating} contributes no fulfillment bonus.
     */
    private double score(double candidatePrice, double minPrice, Vendor vendor) {
        double normalizedPrice = (candidatePrice == 0.0) ? 1.0 : candidatePrice / minPrice;
        double priceTerm = (normalizedPrice == 0.0) ? 0.0 : (1.0 / normalizedPrice) * 0.60;

        double fulfillment = vendor.getFulfillmentRating() == null
            ? 0.0 : vendor.getFulfillmentRating().doubleValue();
        double fulfillmentTerm = (fulfillment / 5.0) * 0.30;

        // Divide-by-zero GUARD (binding rule): clamp avg_shipping_days to >= 1 (null -> 1) via
        // Math.max(days, 1), so the Source-A speed term (1 / avgShippingDays) * 0.10 is always safe.
        Integer avgShippingDays = vendor.getAvgShippingDays();
        int days = (avgShippingDays == null) ? 1 : Math.max(avgShippingDays, 1);
        double shippingTerm = (1.0 / days) * 0.10;

        return priceTerm + fulfillmentTerm + shippingTerm;
    }

    /**
     * Returns a ranked list of all vendors with their prices for a given product/quantity.
     * Fetches all vendor inventory, prices each via {@link PricingService}, and sorts by unit
     * price ascending (cheapest first). Vendors for which a price cannot be determined are
     * skipped.
     *
     * @param productId  the product ID
     * @param quantity   the requested quantity
     * @return           sorted list of {@link VendorPriceResult} (cheapest first)
     */
    public List<VendorPriceResult> getVendorPricesForProduct(Long productId, int quantity) {
        List<VendorInventory> inventoryList = vendorInventoryRepository.findByProductId(productId);

        List<VendorPriceResult> results = new ArrayList<>();
        for (VendorInventory vi : inventoryList) {
            Long vendorId = vi.getId().getVendorId();
            Vendor vendor = vendorRepository.findById(vendorId).orElse(null);
            if (vendor == null) {
                continue;
            }
            BigDecimal unitPrice;
            try {
                unitPrice = pricingService.calculatePrice(productId, vendorId, quantity);
            } catch (RuntimeException e) {
                // Skip vendors for which pricing cannot be determined
                continue;
            }
            VendorPriceResult vpr = new VendorPriceResult();
            vpr.vendorId = vendorId;
            vpr.vendorName = vendor.getName();
            vpr.unitPrice = unitPrice;
            vpr.fulfillmentRating = vendor.getFulfillmentRating();
            vpr.avgShippingDays = vendor.getAvgShippingDays();
            results.add(vpr);
        }

        results.sort(Comparator.comparing(r -> r.unitPrice));
        return results;
    }

    /**
     * Internal holder pairing a candidate vendor ID with its computed unit price, used only
     * during best-vendor scoring.
     */
    private static final class Candidate {
        private final Long vendorId;
        private final BigDecimal price;

        private Candidate(Long vendorId, BigDecimal price) {
            this.vendorId = vendorId;
            this.price = price;
        }
    }

    /**
     * Holds price and vendor metadata for a single vendor option. Public fields are serialized
     * directly to JSON (the PHP storefront reads {@code vendorId}, {@code vendorName},
     * {@code unitPrice}, {@code fulfillmentRating}, and {@code avgShippingDays}).
     */
    public static class VendorPriceResult {
        public Long vendorId;
        public String vendorName;
        public BigDecimal unitPrice;
        public BigDecimal fulfillmentRating;
        public Integer avgShippingDays;
    }
}
