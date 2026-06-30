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
            // Source-A MAXIMIZATION: highest score wins.
            Long bestVendorId = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            double minPriceValue = minPrice.doubleValue();
            for (Map.Entry<Long, BigDecimal> entry : eligiblePrices.entrySet()) {
                Long vendorId = entry.getKey();
                Vendor vendor = vendorRepository.findById(vendorId).orElse(null);
                if (vendor == null) {
                    continue;
                }
                double normPrice = entry.getValue().doubleValue() / minPriceValue;
                double priceScore = (1.0 / normPrice) * 0.60;
                double rating = vendor.getFulfillmentRating() != null
                        ? vendor.getFulfillmentRating().doubleValue() : 0.0;
                double ratingScore = (rating / 5.0) * 0.30;
                Integer shippingDays = vendor.getAvgShippingDays();
                double shippingScore = (shippingDays != null && shippingDays > 0)
                        ? (1.0 / shippingDays) * 0.10 : 0.0;
                double score = priceScore + ratingScore + shippingScore;
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
     * Fetches vendor inventory, prices each via {@link PricingService}, and sorts by unit price
     * ascending. Vendors whose pricing cannot be determined are skipped.
     *
     * @param productId the product ID
     * @param quantity  the requested quantity
     * @return sorted list of VendorPriceResult (cheapest first)
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

        results.sort(Comparator.comparing(r -> r.unitPrice));
        return results;
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
