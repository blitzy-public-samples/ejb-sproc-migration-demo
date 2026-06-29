package org.jboss.as.quickstarts.kitchensink.data;

import java.math.BigDecimal;
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

    /**
     * Returns, in a SINGLE query, every vendor candidate for a product together with the exact data
     * {@code VendorSelectionService} needs to price and score it: the vendor's id/name/fulfillment
     * rating/avg shipping days, the inventory's markup and available stock, and the product's base
     * price.
     *
     * <p>PERFORMANCE FIX (N+1 elimination): this replaces the former {@code findByProductId(...)} which
     * returned only {@code VendorInventory} rows and forced the service to (a) call
     * {@code pricingService.calculatePrice(...)} per candidate — itself reloading product + inventory —
     * and (b) call {@code vendorRepository.findById(...)} per candidate to obtain vendor metadata. The
     * service can now price every candidate from this projection's already-loaded {@code basePrice} and
     * {@code markupPercent} (via {@code PricingService.calculateUnitPrice}) and score it from the
     * projected vendor metrics, issuing exactly ONE query per product instead of ~2N+1.</p>
     *
     * <p>The model entities use plain {@code Long} foreign keys with NO mapped JPA associations, so the
     * vendor and product are joined with explicit ad-hoc entity {@code JOIN ... ON} clauses on the
     * composite-key sub-paths {@code vi.id.vendorId} / {@code vi.id.productId} (supported by Hibernate 6
     * / JPA 3.1). The {@code AS} aliases map to the {@link VendorCandidateProjection} getter names.</p>
     *
     * @param productId the product whose vendor candidates are requested
     * @return one {@link VendorCandidateProjection} per vendor that stocks the product
     */
    @Query("SELECT vi.id.vendorId AS vendorId, v.name AS vendorName, "
         + "vi.markupPercent AS markupPercent, vi.quantityAvailable AS quantityAvailable, "
         + "v.fulfillmentRating AS fulfillmentRating, v.avgShippingDays AS avgShippingDays, "
         + "p.basePrice AS basePrice "
         + "FROM VendorInventory vi "
         + "JOIN Vendor v ON v.id = vi.id.vendorId "
         + "JOIN Product p ON p.id = vi.id.productId "
         + "WHERE vi.id.productId = :productId")
    List<VendorCandidateProjection> findCandidatesByProductId(@Param("productId") Long productId);

    /**
     * Spring Data interface projection for a single vendor candidate of a product. The query's
     * {@code AS} aliases map one-to-one to these getters; values mirror the source columns'
     * nullability ({@code markupPercent}, {@code fulfillmentRating}, {@code avgShippingDays} are
     * nullable and are handled defensively by the consuming service).
     */
    interface VendorCandidateProjection {
        Long getVendorId();
        String getVendorName();
        BigDecimal getMarkupPercent();
        Integer getQuantityAvailable();
        BigDecimal getFulfillmentRating();
        Integer getAvgShippingDays();
        BigDecimal getBasePrice();
    }
}
