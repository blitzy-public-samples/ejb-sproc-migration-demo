package org.jboss.as.quickstarts.kitchensink.marketplace.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.jboss.as.quickstarts.kitchensink.marketplace.exception.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.Product;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.VendorInventory;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.VendorInventoryId;
import org.jboss.as.quickstarts.kitchensink.marketplace.repository.ProductRepository;
import org.jboss.as.quickstarts.kitchensink.marketplace.repository.VendorInventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PricingService — pure-Java reimplementation of the {@code calculate_price} PL/pgSQL stored
 * procedure (see {@code db/02_stored_procedures.sql} lines 19-61, reference only, never invoked
 * at runtime).
 *
 * <p>Converted from the monolith's {@code @ApplicationScoped} CDI bean that delegated to the
 * database via a native {@code SELECT calculate_price(...)} call. That native call is removed;
 * the markup/volume-discount arithmetic now runs in Java against Spring Data repositories.
 * Rounding (HALF_UP, scale 4) and the missing-inventory exception semantics are preserved
 * exactly.</p>
 */
@Service
public class PricingService {

    private final ProductRepository productRepository;
    private final VendorInventoryRepository vendorInventoryRepository;

    public PricingService(ProductRepository productRepository,
                          VendorInventoryRepository vendorInventoryRepository) {
        this.productRepository = productRepository;
        this.vendorInventoryRepository = vendorInventoryRepository;
    }

    /**
     * Calculates the unit price for a product from a specific vendor at a given quantity.
     * Pure-Java reimplementation of {@code calculate_price(product_id, vendor_id, quantity)}.
     *
     * <p>Logic: {@code base_price * (1 + markup% / 100) * (1 - volume_discount%)} rounded to
     * 4 decimal places (HALF_UP). Volume tiers: qty&gt;=100-&gt;15%, qty&gt;=50-&gt;10%,
     * qty&gt;=20-&gt;5%, qty&gt;=10-&gt;2%, else 0%.</p>
     *
     * @param productId the product ID
     * @param vendorId  the vendor ID
     * @param quantity  the order quantity (affects the volume-discount tier)
     * @return the unit price as BigDecimal (scale 4)
     * @throws InventoryNotFoundException if no vendor_inventory row exists for the
     *                                    (product, vendor) pair (was SQL ERRCODE 'P0001')
     */
    @Transactional(readOnly = true)
    public BigDecimal calculatePrice(Long productId, Long vendorId, int quantity) {
        VendorInventory inventory = vendorInventoryRepository
                .findById(new VendorInventoryId(vendorId, productId))
                .orElseThrow(() -> new InventoryNotFoundException(productId, vendorId));

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new InventoryNotFoundException(productId, vendorId));

        BigDecimal basePrice = product.getBasePrice();
        BigDecimal markupPercent = inventory.getMarkupPercent();

        BigDecimal volumeDiscount;
        if (quantity >= 100) {
            volumeDiscount = new BigDecimal("0.15");
        } else if (quantity >= 50) {
            volumeDiscount = new BigDecimal("0.10");
        } else if (quantity >= 20) {
            volumeDiscount = new BigDecimal("0.05");
        } else if (quantity >= 10) {
            volumeDiscount = new BigDecimal("0.02");
        } else {
            volumeDiscount = BigDecimal.ZERO;
        }

        // base_price * (1 + markup/100) * (1 - volume_discount), rounded to 4 dp HALF_UP.
        // markupPercent.movePointLeft(2) is an exact divide-by-100 for the NUMERIC(6,2) markup.
        BigDecimal markupFactor = BigDecimal.ONE.add(markupPercent.movePointLeft(2));
        BigDecimal discountFactor = BigDecimal.ONE.subtract(volumeDiscount);
        return basePrice
                .multiply(markupFactor)
                .multiply(discountFactor)
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Calculates the total price for a line item (unit price * quantity).
     *
     * @param productId the product ID
     * @param vendorId  the vendor ID
     * @param quantity  the order quantity
     * @return the line total as BigDecimal
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateLineTotal(Long productId, Long vendorId, int quantity) {
        BigDecimal unitPrice = calculatePrice(productId, vendorId, quantity);
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
