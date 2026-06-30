package org.jboss.as.quickstarts.kitchensink.marketplace.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import org.jboss.as.quickstarts.kitchensink.marketplace.exception.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.Product;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.VendorInventory;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.VendorInventoryId;
import org.jboss.as.quickstarts.kitchensink.marketplace.repository.ProductRepository;
import org.jboss.as.quickstarts.kitchensink.marketplace.repository.VendorInventoryRepository;
import org.springframework.stereotype.Service;

/**
 * PricingService - computes the unit price for a product from a specific vendor
 * at a given order quantity.
 *
 * <p>This is a faithful Java extraction of the {@code calculate_price()} PL/pgSQL
 * stored procedure (db/02_stored_procedures.sql, lines 21-61). The stored procedure
 * is retained in the database as reference documentation only and is NO LONGER
 * invoked by the application (zero native queries, per the migration rules).</p>
 */
@Service
public class PricingService {

    private final ProductRepository productRepository;
    private final VendorInventoryRepository vendorInventoryRepository;

    // Constructor injection (single constructor -> no @Autowired required).
    public PricingService(ProductRepository productRepository,
                          VendorInventoryRepository vendorInventoryRepository) {
        this.productRepository = productRepository;
        this.vendorInventoryRepository = vendorInventoryRepository;
    }

    /**
     * Calculates the unit price for a product from a specific vendor at a given quantity.
     *
     * <p>Reproduces calculate_price(product_id, vendor_id, quantity):
     * {@code base_price * (1 + markup_percent/100) * (1 - volume_discount)}, rounded to 4 dp.
     * Volume tiers: qty&gt;=100-&gt;15%, qty&gt;=50-&gt;10%, qty&gt;=20-&gt;5%, qty&gt;=10-&gt;2%, else 0%.</p>
     *
     * @param productId the product ID
     * @param vendorId  the vendor ID
     * @param quantity  the order quantity (affects the volume-discount tier)
     * @return the unit price as BigDecimal (scale 4, HALF_UP)
     * @throws InventoryNotFoundException if the product or its vendor_inventory row is absent
     */
    public BigDecimal calculatePrice(Long productId, Long vendorId, int quantity) {
        // Reproduces calculate_price (db/02_stored_procedures.sql L21-61):
        // products JOIN vendor_inventory ON product_id WHERE p.id = productId AND vi.vendor_id = vendorId.
        // The SQL "RAISE EXCEPTION ... ERRCODE 'P0001'" on NOT FOUND becomes InventoryNotFoundException (HTTP 404).
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new InventoryNotFoundException(
                "No vendor inventory found for product " + productId + " vendor " + vendorId));

        // Composite-key constructor argument order is (vendorId, productId).
        VendorInventory inventory = vendorInventoryRepository
            .findById(new VendorInventoryId(vendorId, productId))
            .orElseThrow(() -> new InventoryNotFoundException(
                "No vendor inventory found for product " + productId + " vendor " + vendorId));

        BigDecimal basePrice = product.getBasePrice();
        BigDecimal markupPercent = inventory.getMarkupPercent();

        // Volume discount tiers - preserved EXACTLY from the stored procedure.
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

        // unit = base_price * (1 + markup_percent/100) * (1 - volume_discount); ROUND(..., 4) HALF_UP.
        // DECIMAL64 gives a safe intermediate precision; final scale matches the SQL ROUND(.,4).
        BigDecimal markupMultiplier = BigDecimal.ONE.add(
            markupPercent.divide(BigDecimal.valueOf(100), MathContext.DECIMAL64));
        BigDecimal discountMultiplier = BigDecimal.ONE.subtract(volumeDiscount);

        return basePrice
            .multiply(markupMultiplier, MathContext.DECIMAL64)
            .multiply(discountMultiplier, MathContext.DECIMAL64)
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
    public BigDecimal calculateLineTotal(Long productId, Long vendorId, int quantity) {
        BigDecimal unitPrice = calculatePrice(productId, vendorId, quantity);
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
