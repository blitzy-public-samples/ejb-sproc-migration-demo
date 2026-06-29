package org.jboss.as.quickstarts.kitchensink.data;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import org.jboss.as.quickstarts.kitchensink.model.ShippingZone;

/**
 * Spring Data JPA repository for {@link ShippingZone}.
 *
 * New in the Spring Boot migration. Supports the Java re-implementation of the calculate_shipping
 * stored procedure. Rather than push the dialect-specific SUBSTRING(...)::INTEGER ZIP-prefix range
 * comparison into JPQL (not portable across PostgreSQL and the test DB), this repository returns the
 * zones ordered by id and ShippingService performs the 3-digit numeric prefix bracketing and
 * first-match-by-id selection in Java. Observable contract: pick the first zone (lowest id) whose
 * start/end 3-digit range brackets the ZIP prefix; if none, ShippingService falls back to base rate 1.50.
 */
public interface ShippingZoneRepository extends JpaRepository<ShippingZone, Long> {

    // All zones ordered by id ascending; ShippingService brackets by 3-digit ZIP prefix and takes the
    // first match (mirrors the stored procedure's "ORDER BY id LIMIT 1").
    List<ShippingZone> findAllByOrderByIdAsc();
}
