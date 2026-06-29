package org.jboss.as.quickstarts.kitchensink.data;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import org.jboss.as.quickstarts.kitchensink.model.Vendor;

/**
 * Spring Data JPA repository for {@link Vendor}.
 *
 * Migrated from a Jakarta EE CDI bean (@ApplicationScoped + @Inject EntityManager + JPQL) to a
 * Spring Data interface. CRUD inherited from JpaRepository.
 *
 * NOTE: the former findInventoryForProduct(...) VendorInventory query has been RELOCATED to the new
 * VendorInventoryRepository (findByProductId) — VendorInventory has its own composite-key repository.
 */
public interface VendorRepository extends JpaRepository<Vendor, Long> {

    // Was findAll() via JPQL "SELECT v FROM Vendor v ORDER BY v.fulfillmentRating DESC".
    List<Vendor> findAllByOrderByFulfillmentRatingDesc();
}
