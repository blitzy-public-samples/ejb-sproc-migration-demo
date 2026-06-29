package org.jboss.as.quickstarts.kitchensink.data;

import org.springframework.data.jpa.repository.JpaRepository;

import org.jboss.as.quickstarts.kitchensink.model.DiscountAudit;

/**
 * Spring Data JPA repository for {@link DiscountAudit}.
 *
 * New in the Spring Boot migration. Supports DiscountService, which preserves the observable audit
 * side effect of the apply_customer_discount stored procedure by persisting one DiscountAudit row per
 * calculateDiscount(...) call via the inherited save(...). The inherited count() backs the
 * DiscountServiceIT assertion that the audit count increases by exactly one per call. No custom query
 * methods are required (Minimal Change Clause).
 */
public interface DiscountAuditRepository extends JpaRepository<DiscountAudit, Long> {
}
