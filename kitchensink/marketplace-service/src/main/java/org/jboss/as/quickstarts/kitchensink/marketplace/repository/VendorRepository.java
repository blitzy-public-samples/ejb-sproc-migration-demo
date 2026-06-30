package org.jboss.as.quickstarts.kitchensink.marketplace.repository;

import java.util.List;

import org.jboss.as.quickstarts.kitchensink.marketplace.model.Vendor;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.VendorInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link Vendor} entities (marketplace catalog/pricing context).
 *
 * <p>Replaces the monolith's hand-rolled {@code @ApplicationScoped} +
 * {@code @Inject EntityManager} repository. The inherited {@code findById(Long)} supersedes the
 * monolith's {@code em.find(Vendor.class, id)}.</p>
 */
public interface VendorRepository extends JpaRepository<Vendor, Long> {

    /**
     * Returns all vendors ordered by fulfillment rating descending — preserves the monolith's
     * {@code "SELECT v FROM Vendor v ORDER BY v.fulfillmentRating DESC"}.
     */
    List<Vendor> findAllByOrderByFulfillmentRatingDesc();

    /**
     * Returns the vendor-inventory rows for a given product — preserves the monolith's
     * {@code "SELECT vi FROM VendorInventory vi WHERE vi.id.productId = :productId"} so the
     * inventory listing remains reachable for {@code VendorSelectionService}.
     */
    @Query("SELECT vi FROM VendorInventory vi WHERE vi.id.productId = :productId")
    List<VendorInventory> findInventoryForProduct(@Param("productId") Long productId);
}
