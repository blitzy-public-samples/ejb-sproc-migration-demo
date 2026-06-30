package org.jboss.as.quickstarts.kitchensink.marketplace.repository;

import java.util.List;

import org.jboss.as.quickstarts.kitchensink.marketplace.model.VendorInventory;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.VendorInventoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link VendorInventory} entities, keyed by the composite
 * {@link VendorInventoryId} (vendor_id, product_id).
 *
 * <p>The inherited {@code findById(VendorInventoryId)} is the primary lookup used by
 * {@code PricingService} to read the markup percent for a specific product+vendor pair. The
 * product-scoped {@code findByProductId} listing supports {@code VendorSelectionService}'s
 * vendor ranking. Spring Data auto-implements this interface; no {@code @Repository} stereotype
 * or {@code EntityManager} is required.</p>
 */
public interface VendorInventoryRepository extends JpaRepository<VendorInventory, VendorInventoryId> {

    /**
     * Returns all vendor-inventory rows for a given product — mirrors the monolith's
     * {@code "SELECT vi FROM VendorInventory vi WHERE vi.id.productId = :productId"}.
     */
    @Query("SELECT vi FROM VendorInventory vi WHERE vi.id.productId = :productId")
    List<VendorInventory> findByProductId(@Param("productId") Long productId);
}
