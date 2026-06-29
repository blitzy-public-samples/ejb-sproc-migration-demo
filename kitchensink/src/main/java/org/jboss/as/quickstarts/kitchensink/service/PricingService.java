package org.jboss.as.quickstarts.kitchensink.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.jboss.as.quickstarts.kitchensink.data.ProductRepository;
import org.jboss.as.quickstarts.kitchensink.data.VendorInventoryRepository;
import org.jboss.as.quickstarts.kitchensink.model.Product;
import org.jboss.as.quickstarts.kitchensink.model.VendorInventory;
import org.jboss.as.quickstarts.kitchensink.model.VendorInventoryId;

/**
 * PricingService — pure-Java re-implementation of the {@code calculate_price(product_id,
 * vendor_id, quantity)} PL/pgSQL stored procedure.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): this was a CDI
 * {@code @ApplicationScoped} bean that delegated to the database via a native
 * {@code SELECT calculate_price(...)} query through an injected {@code EntityManager}. The
 * business logic now lives in the application tier per the migration's core objective — no
 * stored procedure is invoked. The bean is a Spring {@code @Service} singleton.</p>
 *
 * <p>This service is intentionally <strong>stateless</strong> (its only fields are the two
 * injected, immutable repositories) so it can be safely shared as a singleton across
 * {@code OrderService}, {@code DiscountService}, and {@code VendorSelectionService}. It depends
 * only on repositories (never on other services), making it a leaf in the service dependency
 * graph and guaranteeing acyclic constructor wiring.</p>
 */
@Service
public class PricingService {

    private final ProductRepository productRepository;
    private final VendorInventoryRepository vendorInventoryRepository;

    /**
     * Constructor injection replaces the former CDI {@code @Inject EntityManager} field
     * injection, yielding immutable collaborators and trivially testable construction.
     */
    public PricingService(ProductRepository productRepository,
                          VendorInventoryRepository vendorInventoryRepository) {
        this.productRepository = productRepository;
        this.vendorInventoryRepository = vendorInventoryRepository;
    }

    /**
     * Calculates the unit price for a product from a specific vendor at a given quantity.
     *
     * <p>Re-implements {@code calculate_price}:
     * {@code base_price * (1 + markup_percent/100) * (1 - volume_discount)} rounded to 4
     * decimal places (HALF_UP), matching the stored procedure's {@code ROUND(..., 4)} and the
     * {@code products.base_price NUMERIC(12,4)} column scale. Volume-discount tiers:
     * qty&ge;100 -&gt; 15%, &ge;50 -&gt; 10%, &ge;20 -&gt; 5%, &ge;10 -&gt; 2%, otherwise 0%.</p>
     *
     * @param productId  the product ID
     * @param vendorId   the vendor ID
     * @param quantity   the order quantity (affects the volume-discount tier)
     * @return           the unit price as a BigDecimal (scale 4)
     * @throws InventoryNotFoundException if no inventory row exists for the product/vendor
     *         combination (the Java equivalent of the stored procedure's {@code RAISE
     *         EXCEPTION ... ERRCODE = 'P0001'})
     */
    public BigDecimal calculatePrice(Long productId, Long vendorId, int quantity) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new InventoryNotFoundException(
                "No vendor inventory found for product " + productId + " vendor " + vendorId));

        VendorInventory inventory = vendorInventoryRepository
            .findById(new VendorInventoryId(vendorId, productId))
            .orElseThrow(() -> new InventoryNotFoundException(
                "No vendor inventory found for product " + productId + " vendor " + vendorId));

        BigDecimal basePrice = product.getBasePrice();
        BigDecimal markupPercent = inventory.getMarkupPercent();
        if (markupPercent == null) {
            markupPercent = BigDecimal.ZERO;
        }

        BigDecimal volumeDiscount = volumeDiscountFor(quantity);

        // base_price * (1 + markup_percent/100.0) * (1 - volume_discount)
        BigDecimal markupFactor = BigDecimal.ONE.add(
            markupPercent.divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP));
        BigDecimal volumeFactor = BigDecimal.ONE.subtract(volumeDiscount);

        return basePrice.multiply(markupFactor)
            .multiply(volumeFactor)
            .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Maps an order quantity to its volume-discount fraction, mirroring the stored procedure's
     * tiered {@code IF ... ELSIF} ladder exactly.
     *
     * @param quantity the order quantity
     * @return the volume-discount fraction (0.00, 0.02, 0.05, 0.10, or 0.15)
     */
    private BigDecimal volumeDiscountFor(int quantity) {
        if (quantity >= 100) {
            return new BigDecimal("0.15");
        } else if (quantity >= 50) {
            return new BigDecimal("0.10");
        } else if (quantity >= 20) {
            return new BigDecimal("0.05");
        } else if (quantity >= 10) {
            return new BigDecimal("0.02");
        }
        return BigDecimal.ZERO;
    }

    /**
     * Calculates the total price for a line item (unit price * quantity).
     *
     * @param productId  the product ID
     * @param vendorId   the vendor ID
     * @param quantity   the order quantity
     * @return           the line total as BigDecimal
     */
    public BigDecimal calculateLineTotal(Long productId, Long vendorId, int quantity) {
        BigDecimal unitPrice = calculatePrice(productId, vendorId, quantity);
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
