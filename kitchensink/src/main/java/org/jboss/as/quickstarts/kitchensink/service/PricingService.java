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

        // base_price NUMERIC(12,4); markup_percent NUMERIC(6,2) — nullable. The arithmetic (null-markup
        // guard, volume tier, rounding) is delegated to the shared, repository-free calculateUnitPrice(...)
        // below so there is ONE authoritative pricing formula. This repository-backed entry point keeps the
        // P0001-equivalent not-found contract above; both paths compute identical unit prices.
        return calculateUnitPrice(product.getBasePrice(), inventory.getMarkupPercent(), quantity);
    }

    /**
     * Computes a unit price from already-loaded {@code base_price} and {@code markup_percent} values,
     * performing NO repository access. This is the single authoritative pricing formula, shared by the
     * repository-backed {@link #calculatePrice(Long, Long, int)} and by
     * {@code VendorSelectionService}, which prices many candidate vendors from a single projection query
     * (the order-orchestration N+1 fix) without reloading product/inventory rows per candidate.
     *
     * <p>Mirrors the SQL {@code ROUND(base * (1 + markup/100) * (1 - volume_discount), 4)}: the unit
     * price is rounded to scale 4 with HALF_UP, matching {@code order_items.unit_price NUMERIC(12,4)}.
     * {@code markup / 100} is expressed as {@code multiply(new BigDecimal("0.01"))} to avoid any
     * non-terminating-division risk while remaining exact for the schema's scales. A null
     * {@code markupPercent} (the column is nullable in db/01_schema.sql) is treated as 0% to produce
     * predictable pricing instead of a NullPointerException.</p>
     *
     * <p>Pure function — introduces no field and reads no mutable state, preserving the stateless
     * shared-singleton contract (AAP §0.6.3).</p>
     *
     * @param basePrice      the product's {@code base_price} (must be non-null, as in the schema)
     * @param markupPercent  the vendor's {@code markup_percent} (nullable -> treated as 0%)
     * @param quantity       the order quantity (selects the volume-discount tier)
     * @return               the unit price as a {@link BigDecimal} (scale 4, HALF_UP)
     */
    public BigDecimal calculateUnitPrice(BigDecimal basePrice, BigDecimal markupPercent, int quantity) {
        // DEFENSIVE GUARD: treat a null markup as 0% (no markup) — see method Javadoc.
        BigDecimal effectiveMarkup = (markupPercent == null) ? BigDecimal.ZERO : markupPercent;

        BigDecimal volumeDiscount = volumeDiscountForQuantity(quantity);

        // unit = base * (1 + markup/100) * (1 - volumeDiscount), ROUND(..., 4).
        BigDecimal markupFactor = BigDecimal.ONE.add(effectiveMarkup.multiply(new BigDecimal("0.01")));
        BigDecimal volumeFactor = BigDecimal.ONE.subtract(volumeDiscount);
        return basePrice.multiply(markupFactor).multiply(volumeFactor)
            .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Returns the volume-discount fraction for a quantity, mirroring the SQL IF/ELSIF ladder:
     * qty &gt;= 100 -&gt; 0.15, &gt;= 50 -&gt; 0.10, &gt;= 20 -&gt; 0.05, &gt;= 10 -&gt; 0.02, otherwise 0.
     * Exact decimals are used so the multiplication remains exact at the schema's scale.
     *
     * @param quantity the order quantity
     * @return the volume-discount fraction as an exact {@link BigDecimal}
     */
    private BigDecimal volumeDiscountForQuantity(int quantity) {
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
