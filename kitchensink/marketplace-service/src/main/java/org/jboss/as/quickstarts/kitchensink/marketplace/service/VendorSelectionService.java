package org.jboss.as.quickstarts.kitchensink.marketplace.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jboss.as.quickstarts.kitchensink.marketplace.model.Vendor;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.VendorInventory;
import org.jboss.as.quickstarts.kitchensink.marketplace.repository.VendorInventoryRepository;
import org.jboss.as.quickstarts.kitchensink.marketplace.repository.VendorRepository;
import org.springframework.stereotype.Service;

/**
 * VendorSelectionService - selects the best vendor for a product/quantity and builds
 * a ranked vendor price list.
 *
 * <p>Faithful Java extraction of the {@code select_best_vendor()} PL/pgSQL stored
 * procedure (db/02_stored_procedures.sql, lines 66-106). The stored procedure is
 * retained only as reference documentation and is no longer invoked (zero native
 * queries). It also uses {@link PricingService} (shared dependency) to price each
 * vendor option.</p>
 */
@Service
public class VendorSelectionService {

    private final VendorRepository vendorRepository;
    private final VendorInventoryRepository vendorInventoryRepository;
    private final PricingService pricingService;

    // Constructor injection (single constructor -> no @Autowired required).
    public VendorSelectionService(VendorRepository vendorRepository,
                                  VendorInventoryRepository vendorInventoryRepository,
                                  PricingService pricingService) {
        this.vendorRepository = vendorRepository;
        this.vendorInventoryRepository = vendorInventoryRepository;
        this.pricingService = pricingService;
    }

    /**
     * Selects the best vendor for a given product and requested quantity.
     *
     * @param productId the product ID
     * @param quantity  the requested quantity
     * @return the vendor ID of the best vendor, or {@code null} if no vendor has any stock
     */
    public Long selectBestVendor(Long productId, int quantity) {
        // Reproduces select_best_vendor (db/02_stored_procedures.sql L66-106) WITHOUT native SQL.
        List<VendorInventory> inventory = vendorInventoryRepository.findByIdProductId(productId);

        // Eligible vendors: enough quantity on hand to satisfy the request.
        List<VendorInventory> eligible = new ArrayList<>();
        for (VendorInventory vi : inventory) {
            if (vi.getQuantityAvailable() != null && vi.getQuantityAvailable() >= quantity) {
                eligible.add(vi);
            }
        }

        if (!eligible.isEmpty()) {
            // Minimum price across eligible vendors, used to normalize the price term.
            BigDecimal minPrice = null;
            for (VendorInventory vi : eligible) {
                BigDecimal price = pricingService.calculatePrice(
                    productId, vi.getId().getVendorId(), quantity);
                if (minPrice == null || price.compareTo(minPrice) < 0) {
                    minPrice = price;
                }
            }

            // Source A (prompt) mandates HIGHEST weighted score; supersedes the stored
            // procedure's lowest-score ORDER BY ... LIMIT 1 ASCENDING.
            // Reward formula: (1.0/normalizedPrice)*0.60 + (fulfillmentRating/5.0)*0.30 + (1.0/avgShippingDays)*0.10
            // (use 1.0/avgShippingDays, NOT the SQL's avg_shipping_days/10.0 term).
            Long bestVendorId = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (VendorInventory vi : eligible) {
                Long vendorId = vi.getId().getVendorId();
                Vendor vendor = vendorRepository.findById(vendorId).orElse(null);
                if (vendor == null) {
                    continue; // mirrors the SQL INNER JOIN on vendors
                }
                BigDecimal price = pricingService.calculatePrice(productId, vendorId, quantity);
                double normalizedPrice = price.doubleValue() / minPrice.doubleValue();
                double score = (1.0 / normalizedPrice) * 0.60
                    + (vendor.getFulfillmentRating().doubleValue() / 5.0) * 0.30
                    + (1.0 / vendor.getAvgShippingDays()) * 0.10;
                if (score > bestScore) {
                    bestScore = score;
                    bestVendorId = vendorId;
                }
            }
            return bestVendorId;
        }

        // Fallback (no eligible vendor): cheapest vendor with ANY stock (quantity_available > 0).
        // Matches the stored procedure's fallback branch.
        Long fallbackVendorId = null;
        BigDecimal lowestPrice = null;
        for (VendorInventory vi : inventory) {
            if (vi.getQuantityAvailable() == null || vi.getQuantityAvailable() <= 0) {
                continue;
            }
            Long vendorId = vi.getId().getVendorId();
            BigDecimal price = pricingService.calculatePrice(productId, vendorId, quantity);
            if (lowestPrice == null || price.compareTo(lowestPrice) < 0) {
                lowestPrice = price;
                fallbackVendorId = vendorId;
            }
        }
        return fallbackVendorId; // null if no stock at all
    }

    /**
     * Returns a ranked list of all vendors with their prices for a given product/quantity,
     * sorted by unit price ascending. Vendors that cannot be resolved or priced are skipped.
     *
     * @param productId the product ID
     * @param quantity  the requested quantity
     * @return sorted list of {@link VendorPriceResult} (cheapest first)
     */
    public List<VendorPriceResult> getVendorPricesForProduct(Long productId, int quantity) {
        List<VendorInventory> inventoryList = vendorInventoryRepository.findByIdProductId(productId);

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
                // Skip vendors for which pricing cannot be determined.
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
