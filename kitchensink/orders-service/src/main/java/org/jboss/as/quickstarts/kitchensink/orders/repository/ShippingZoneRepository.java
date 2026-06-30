package org.jboss.as.quickstarts.kitchensink.orders.repository;

import org.jboss.as.quickstarts.kitchensink.orders.model.ShippingZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link ShippingZone} (the {@code shipping_zones} table),
 * owned by orders-service.
 *
 * <p>CRUD is inherited from {@link JpaRepository}. The native query below reproduces the
 * zone-selection logic of the {@code calculate_shipping} stored procedure
 * (db/02_stored_procedures.sql L169-174) so {@code ShippingService} can price shipping in
 * pure Java without ever calling the procedure.</p>
 */
public interface ShippingZoneRepository extends JpaRepository<ShippingZone, Long> {

    /**
     * Returns the single best-matching shipping zone for a 3-digit ZIP prefix, or {@code null}
     * when no zone matches.
     *
     * <p>Reproduces the {@code calculate_shipping} zone lookup: matches the row whose
     * {@code zip_range_start} 3-digit prefix is &lt;= the destination prefix AND whose
     * {@code zip_range_end} 3-digit prefix is &gt;= the destination prefix, ordered by {@code id},
     * taking the first match. Implemented as a native query because it needs {@code SUBSTRING} +
     * integer-cast arithmetic over the {@code VARCHAR} ZIP-range columns; it uses the frozen
     * table/column names from {@code db/01_schema.sql}. This is a plain {@code SELECT}, NOT a
     * stored-procedure call.</p>
     *
     * <p>{@code service/ShippingService} computes {@code zipPrefix} (first 3 digits of the
     * destination ZIP as an int; malformed ZIP &rarr; 0), then reads {@code baseRatePerLb} from the
     * returned zone, applying the {@code $1.50/lb} fallback when this method returns {@code null}.</p>
     *
     * @param zipPrefix the destination ZIP's first three digits as an integer
     * @return the matching {@link ShippingZone}, or {@code null} if none matches
     */
    @Query(value = "SELECT * FROM shipping_zones "
            + "WHERE CAST(SUBSTRING(zip_range_start, 1, 3) AS INTEGER) <= :zipPrefix "
            + "AND CAST(SUBSTRING(zip_range_end, 1, 3) AS INTEGER) >= :zipPrefix "
            + "ORDER BY id LIMIT 1", nativeQuery = true)
    ShippingZone findZoneByZipPrefix(@Param("zipPrefix") int zipPrefix);
}
