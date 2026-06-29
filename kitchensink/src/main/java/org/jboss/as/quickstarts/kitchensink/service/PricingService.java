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
 * PricingService — embodies the {@code calculate_price} stored procedure
 * (db/02_stored_procedures.sql L21-61).
 *
 * <p>Migrated from Jakarta EE CDI (@ApplicationScoped + @Inject EntityManager + a native
 * {@code "SELECT calculate_price(...)"} query) to a Spring {@code @Service}. The pricing formula is
 * now computed in Java from repository-loaded data — there is NO native stored-procedure call and NO
 * {@code EntityManager}. The stored procedure remains in db/02_stored_procedures.sql only as a
 * behavioral reference and is no longer invoked by the application.</p>
 *
 * <p>LEAF + STATELESS: this service depends only on two repositories (no other services) and holds no
 * mutable state, so it is safe to share as a Spring singleton across {@code OrderService},
 * {@code DiscountService}, and {@code VendorSelectionService} (AAP §0.6.3). Keeping it stateless is a
 * binding rule — do not add caches, counters, or any mutable fields.</p>
 */
@Service
public class PricingService {

    // Immutable collaborators (replaces CDI @Inject field injection). The only fields permitted —
    // both repositories, no services, no mutable state — preserving the stateless-singleton contract.
    private final ProductRepository productRepository;
    private final VendorInventoryRepository vendorInventoryRepository;

    /**
     * Single constructor → Spring performs constructor injection automatically (no {@code @Autowired}
     * annotation required). Replaces the former CDI {@code @Inject EntityManager} field injection.
     *
     * @param productRepository          repository providing the product's {@code base_price}
     * @param vendorInventoryRepository  repository providing the vendor's {@code markup_percent}
     */
    public PricingService(ProductRepository productRepository,
                          VendorInventoryRepository vendorInventoryRepository) {
        this.productRepository = productRepository;
        this.vendorInventoryRepository = vendorInventoryRepository;
    }

    /**
     * Calculates the unit price for a product from a specific vendor at a given quantity.
     *
     * <p>Re-implementation of the {@code calculate_price(product_id, vendor_id, quantity)} stored
     * procedure: load {@code base_price} (from {@link Product}) and {@code markup_percent} (from
     * {@link VendorInventory}); a missing product or a missing vendor+product inventory combination
     * surfaces as {@link InventoryNotFoundException} — the Java equivalent of the SQL
     * {@code RAISE EXCEPTION ... USING ERRCODE = 'P0001'}.</p>
     *
     * <p>Formula (mirrors the SQL {@code ROUND(base * (1 + markup/100) * (1 - volume_discount), 4)}):
     * the unit price is rounded to scale 4 with HALF_UP, matching {@code order_items.unit_price
     * NUMERIC(12,4)}. {@code markup / 100} is expressed as {@code multiply(new BigDecimal("0.01"))}
     * to avoid any non-terminating-division risk while remaining exact for the schema's scales.</p>
     *
     * @param productId  the product ID
     * @param vendorId   the vendor ID
     * @param quantity   the order quantity (selects the volume-discount tier)
     * @return           the unit price as a {@link BigDecimal} (scale 4, HALF_UP)
     * @throws InventoryNotFoundException if the product, or the vendor+product inventory row, is absent
     */
    public BigDecimal calculatePrice(Long productId, Long vendorId, int quantity) {
        // Load base_price (Product) — missing product => P0001-equivalent not-found.
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new InventoryNotFoundException(
                "No product found: " + productId));

        // Load markup_percent (VendorInventory) for the (vendor, product) composite key. The
        // VendorInventoryId constructor order is (vendorId, productId) — verified in the model.
        VendorInventory inventory = vendorInventoryRepository
            .findById(new VendorInventoryId(vendorId, productId))
            .orElseThrow(() -> new InventoryNotFoundException(
                "No vendor inventory found for product " + productId + " vendor " + vendorId));

        BigDecimal basePrice = product.getBasePrice();           // base_price  NUMERIC(12,4)
        BigDecimal markupPercent = inventory.getMarkupPercent(); // markup_percent NUMERIC(6,2)

        // Volume-discount tiers (exact decimals), mirroring the SQL IF/ELSIF ladder:
        // qty >= 100 -> 0.15, >= 50 -> 0.10, >= 20 -> 0.05, >= 10 -> 0.02, otherwise 0.
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

        // unit = base * (1 + markup/100) * (1 - volumeDiscount), ROUND(..., 4).
        BigDecimal markupFactor = BigDecimal.ONE.add(markupPercent.multiply(new BigDecimal("0.01")));
        BigDecimal volumeFactor = BigDecimal.ONE.subtract(volumeDiscount);
        return basePrice.multiply(markupFactor).multiply(volumeFactor)
            .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Calculates the line total for a product from a specific vendor at a given quantity
     * (unit price × quantity).
     *
     * <p>{@code order_items.line_total} is {@code NUMERIC(12,2)}, so the result is rounded to scale 2
     * with HALF_UP, mirroring the SQL {@code ROUND(unit_price * quantity, 2)}.</p>
     *
     * @param productId  the product ID
     * @param vendorId   the vendor ID
     * @param quantity   the order quantity
     * @return           the line total as a {@link BigDecimal} (scale 2, HALF_UP)
     * @throws InventoryNotFoundException if the product, or the vendor+product inventory row, is absent
     */
    public BigDecimal calculateLineTotal(Long productId, Long vendorId, int quantity) {
        return calculatePrice(productId, vendorId, quantity)
            .multiply(BigDecimal.valueOf(quantity))
            .setScale(2, RoundingMode.HALF_UP);
    }
}
