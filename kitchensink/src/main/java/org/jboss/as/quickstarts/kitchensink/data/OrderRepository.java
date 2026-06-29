package org.jboss.as.quickstarts.kitchensink.data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.jboss.as.quickstarts.kitchensink.model.Order;

/**
 * Spring Data JPA repository for {@link Order}.
 *
 * Migrated from a Jakarta EE CDI bean (@ApplicationScoped + @Inject EntityManager + JPQL) to a
 * Spring Data interface. CRUD inherited from JpaRepository.
 *
 * NOTE: the former findItemsByOrderId(...) OrderItem query has been RELOCATED to the new
 * OrderItemRepository (findByOrderId). The sumConfirmedTotalSince(...) query is NEW: it replaces the
 * native COALESCE(SUM(o.total),0) over CONFIRMED orders within the last 90 days that the
 * recalculate_customer_tiers stored procedure performed (db/02_stored_procedures.sql L304-308).
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    // Was findByMemberId(...) via JPQL "... WHERE o.memberId = :memberId ORDER BY o.createdAt DESC".
    List<Order> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    // NEW: mirrors recalculate_customer_tiers() — sum of CONFIRMED order totals since the cutoff.
    // TierRecalculationService passes cutoff = LocalDateTime.now().minusDays(90).
    // COALESCE keeps the result non-null (0) when a member has no qualifying orders.
    @Query("SELECT COALESCE(SUM(o.total), 0) FROM Order o "
         + "WHERE o.memberId = :memberId AND o.status = 'CONFIRMED' AND o.createdAt >= :cutoff")
    BigDecimal sumConfirmedTotalSince(@Param("memberId") Long memberId,
                                      @Param("cutoff") LocalDateTime cutoff);
}
