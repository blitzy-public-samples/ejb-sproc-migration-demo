package org.jboss.as.quickstarts.kitchensink.orders.repository;

import org.jboss.as.quickstarts.kitchensink.orders.model.DiscountAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscountAuditRepository extends JpaRepository<DiscountAudit, Long> {
}
