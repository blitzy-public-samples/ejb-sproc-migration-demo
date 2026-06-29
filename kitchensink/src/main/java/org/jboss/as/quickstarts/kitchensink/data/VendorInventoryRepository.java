package org.jboss.as.quickstarts.kitchensink.data;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.jboss.as.quickstarts.kitchensink.model.VendorInventory;
import org.jboss.as.quickstarts.kitchensink.model.VendorInventoryId;

/**
 * Spring Data JPA repository for {@link VendorInventory} (composite key {@link VendorInventoryId}).
 *
 * New in the Spring Boot migration: holds the inventory-by-product query that was previously
 * VendorRepository.findInventoryForProduct(...) (Jakarta EE CDI + EntityManager JPQL). CRUD is
 * inherited from JpaRepository; findById(VendorInventoryId) returns Optional<VendorInventory>, which
 * PricingService uses with new VendorInventoryId(vendorId, productId) to resolve a vendor+product
 * combination (throwing InventoryNotFoundException when empty).
 */
public interface VendorInventoryRepository extends JpaRepository<VendorInventory, VendorInventoryId> {

    // Relocated from VendorRepository: all inventory rows for a given product id.
    @Query("SELECT vi FROM VendorInventory vi WHERE vi.id.productId = :productId")
    List<VendorInventory> findByProductId(@Param("productId") Long productId);
}
