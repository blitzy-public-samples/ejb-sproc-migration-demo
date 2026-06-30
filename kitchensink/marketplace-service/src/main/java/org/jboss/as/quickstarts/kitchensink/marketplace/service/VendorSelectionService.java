package org.jboss.as.quickstarts.kitchensink.marketplace.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.as.quickstarts.kitchensink.marketplace.model.Vendor;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.VendorInventory;
import org.jboss.as.quickstarts.kitchensink.marketplace.repository.VendorInventoryRepository;
import org.jboss.as.quickstarts.kitchensink.marketplace.repository.VendorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VendorSelectionService — pure-Java reimplementation of the {@code select_best_vendor} PL/pgSQL
 * stored procedure (see {@code db/02_stored_procedures.sql} lines 66-106, reference only, never
 * invoked at runtime). Also builds a ranked vendor-price list via the shared {@link PricingService}.
 *
 * <p>Converted from the monolith's {@code @ApplicationScoped} CDI bean that delegated vendor
 * selection to a native {@code SELECT select_best_vendor(...)} database call and loaded vendor
 * inventory and vendor rows through the JPA persistence context. Those data-access paths are
 * removed; selection and ranking now run in Java against Spring Data repositories.</p>
 *
 * <p><b>Authority note (AAP &sect;0.6.1 / &sect;0.7.3):</b> the scoring uses the Source-A
 * MAXIMIZATION formula and picks the HIGHEST-scoring vendor. This intentionally OVERRIDES the
 * Source-B SQL, which minimized a different expression. The eligibility filter
 * ({@code quantity_available >= quantity}) and the cheapest-with-any-stock fallback come from
 * Source B (used where Source A is silent).</p>
 */
@Service
public class VendorSelectionService {

    private final VendorInventoryRepository vendorInventoryRepository;
    private final VendorRepository vendorRepository;
    private final PricingService pricingService;

    public VendorSelectionService(VendorInventoryRepository vendorInventoryRepository,
                                  VendorRepository vendorRepository,
                                  PricingService pricingService) {
        this.vendorInventoryRepository = vendorInventoryRepository;
        this.vendorRepository = vendorRepository;
        this.pricingService = pricingService;
    }

    /**
     * Selects the best vendor for a given product and quantity.
     *
     * <p>Source-A maximization: {@code score = (1/normPrice)*0.60 + (rating/5)*0.30 +
     * (1/avgShippingDays)*0.10}, where {@code normPrice = vendorPrice / minEligiblePrice}; the
     * vendor with the HIGHEST score wins. If no vendor has {@code quantity_available >= quantity},
     * falls back to the cheapest vendor with any stock ({@code quantity_available > 0}).</p>
     *
     * @param productId the product ID
     * @param quantity  the requested quantity
     * @return the vendor ID of the best vendor, or {@code null} if none found
     */
    @Transactional(readOnly = true)
    public Long selectBestVendor(Long productId, int quantity) {
        List<VendorInventory> inventoryList = vendorInventoryRepository.findByProductId(productId);

        // Eligible vendors: quantity_available >= requested quantity. Price each and track the
        // minimum eligible price for normalization.
        Map<Long, BigDecimal> eligiblePrices = new LinkedHashMap<>();
        BigDecimal minPrice = null;
        for (VendorInventory vi : inventoryList) {
            Integer available = vi.getQuantityAvailable();
            if (available == null || available < quantity) {
                continue;
            }
            Long vendorId = vi.getId().getVendorId();
            BigDecimal price = pricingService.calculatePrice(productId, vendorId, quantity);
            eligiblePrices.put(vendorId, price);
            if (minPrice == null || price.compareTo(minPrice) < 0) {
                minPrice = price;
            }
        }

        if (!eligiblePrices.isEmpty() && minPrice != null) {
            // Source-A MAXIMIZATION: highest score wins. The scoring is centralized in
            // sourceAScore(...) so this selection path and the getVendorPricesForProduct ranking
            // path use the IDENTICAL formula and can never drift.
            Long bestVendorId = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (Map.Entry<Long, BigDecimal> entry : eligiblePrices.entrySet()) {
                Long vendorId = entry.getKey();
                Vendor vendor = vendorRepository.findById(vendorId).orElse(null);
                if (vendor == null) {
                    continue;
                }
                double score = sourceAScore(entry.getValue(), minPrice,
                        vendor.getFulfillmentRating(), vendor.getAvgShippingDays());
                if (score > bestScore) {
                    bestScore = score;
                    bestVendorId = vendorId;
                }
            }
            if (bestVendorId != null) {
                return bestVendorId;
            }
        }

        // Fallback: cheapest vendor with any stock (quantity_available > 0).
        Long fallbackVendorId = null;
        BigDecimal cheapestPrice = null;
        for (VendorInventory vi : inventoryList) {
            Integer available = vi.getQuantityAvailable();
            if (available == null || available <= 0) {
                continue;
            }
            Long vendorId = vi.getId().getVendorId();
            BigDecimal price = pricingService.calculatePrice(productId, vendorId, quantity);
            if (cheapestPrice == null || price.compareTo(cheapestPrice) < 0) {
                cheapestPrice = price;
                fallbackVendorId = vendorId;
            }
        }
        return fallbackVendorId;
    }

    /**
     * Returns a ranked list of all vendors with their prices for a given product/quantity.
     * Fetches vendor inventory, prices each via {@link PricingService}, and sorts by the Source-A
     * MAXIMIZATION score DESCENDING (AAP &sect;0.6.1) &mdash; the SAME score used by
     * {@link #selectBestVendor(Long, int)} &mdash; so the FIRST entry is the Source-A best vendor,
     * NOT merely the cheapest. Vendors whose pricing cannot be determined are skipped.
     *
     * <p><b>Why this matters (review C1/C2):</b> orders-service selects the best vendor over HTTP via
     * the dedicated {@code /best-vendor} endpoint (which calls {@link #selectBestVendor(Long, int)}).
     * This display list is additionally ranked by the identical Source-A score so the catalog's
     * top-ranked ("best") vendor matches the order-time selection instead of contradicting it with a
     * cheapest-first ordering.</p>
     *
     * <p>Ties are broken deterministically by unit price ascending, then vendor id ascending.</p>
     *
     * @param productId the product ID
     * @param quantity  the requested quantity
     * @return list of VendorPriceResult ranked by Source-A score (best vendor first)
     */
    @Transactional(readOnly = true)
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
            } catch (Exception e) {
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

        // Source-A ranking (AAP §0.6.1): order by the SAME maximization score selectBestVendor uses
        // (highest score first), NOT by raw unit price. Normalization uses the minimum unit price
        // across the listed vendors. Deterministic tie-breakers: unit price asc, then vendor id asc.
        BigDecimal minPrice = results.stream()
                .map(r -> r.unitPrice)
                .filter(p -> p != null)
                .min(Comparator.naturalOrder())
                .orElse(null);
        results.sort(
                Comparator.comparingDouble((VendorPriceResult r) ->
                                sourceAScore(r.unitPrice, minPrice, r.fulfillmentRating, r.avgShippingDays))
                        .reversed()
                        .thenComparing(r -> r.unitPrice)
                        .thenComparing(r -> r.vendorId));
        return results;
    }

    /**
     * Source-A vendor score (AAP &sect;0.6.1, &sect;0.7.3 — OVERRIDES the Source-B SQL minimization):
     * {@code score = (1/normPrice)*0.60 + (rating/5)*0.30 + (1/avgShippingDays)*0.10}, where
     * {@code normPrice = unitPrice / minEligiblePrice}. The vendor with the HIGHEST score is best.
     *
     * <p>Centralized here so {@link #selectBestVendor(Long, int)} (order-time selection) and
     * {@link #getVendorPricesForProduct(Long, int)} (catalog display ranking) compute the score with
     * the identical formula and cannot diverge. Null-safe: a null/zero {@code minEligiblePrice}
     * yields {@code normPrice = 1.0}; a null rating scores 0; a null/non-positive shipping-days
     * contributes 0 to the shipping term (matching the original inline computation).</p>
     *
     * @param unitPrice         this vendor's unit price
     * @param minEligiblePrice  the minimum unit price across the candidate vendors (normalizer)
     * @param fulfillmentRating the vendor's fulfillment rating (0..5; may be {@code null})
     * @param avgShippingDays   the vendor's average shipping days (may be {@code null})
     * @return the Source-A score (higher is better)
     */
    private static double sourceAScore(BigDecimal unitPrice, BigDecimal minEligiblePrice,
                                       BigDecimal fulfillmentRating, Integer avgShippingDays) {
        double minPriceValue = (minEligiblePrice != null) ? minEligiblePrice.doubleValue() : 0.0;
        double unitPriceValue = (unitPrice != null) ? unitPrice.doubleValue() : 0.0;
        double normPrice = (minPriceValue > 0.0) ? (unitPriceValue / minPriceValue) : 1.0;
        double priceScore = (normPrice > 0.0) ? (1.0 / normPrice) * 0.60 : 0.0;
        double rating = (fulfillmentRating != null) ? fulfillmentRating.doubleValue() : 0.0;
        double ratingScore = (rating / 5.0) * 0.30;
        double shippingScore = (avgShippingDays != null && avgShippingDays > 0)
                ? (1.0 / avgShippingDays) * 0.10 : 0.0;
        return priceScore + ratingScore + shippingScore;
    }

    /**
     * Holds price and vendor metadata for a single vendor option.
     */
    public static class VendorPriceResult {
        public Long vendorId;
        public String vendorName;
        public BigDecimal unitPrice;
        public BigDecimal fulfillmentRating;
        public Integer avgShippingDays;
    }
}
