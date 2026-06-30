package org.jboss.as.quickstarts.kitchensink.orders.repository;

import org.jboss.as.quickstarts.kitchensink.orders.model.DiscountAudit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link DiscountAudit} (the {@code discount_audit} table),
 * owned by orders-service.
 *
 * <p>Intentionally declares no custom query methods. {@code service/DiscountService} persists
 * exactly one audit row on EVERY discount calculation &mdash; including non-committing previews
 * (AAP &sect;0.6.3 side-effect preservation) &mdash; via the inherited {@link JpaRepository#save}.
 * This replaces the {@code INSERT INTO discount_audit (...)} that the {@code apply_customer_discount}
 * stored procedure performed.</p>
 */
public interface DiscountAuditRepository extends JpaRepository<DiscountAudit, Long> {
}
