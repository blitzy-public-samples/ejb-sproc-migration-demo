package org.jboss.as.quickstarts.kitchensink.orders.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.jboss.as.quickstarts.kitchensink.orders.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link Order} (the {@code orders} table), owned by orders-service.
 *
 * <p>Replaces the monolith's hand-rolled {@code @ApplicationScoped} {@code EntityManager}/JPQL
 * repository. CRUD (including {@code findById} and {@code save}) is inherited from
 * {@link JpaRepository}; only the two domain-specific finders below are declared.</p>
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Order history for a member, newest first.
     *
     * <p>Preserves the monolith {@code OrderRepository.findByMemberId} query
     * ({@code "SELECT o FROM Order o WHERE o.memberId = :memberId ORDER BY o.createdAt DESC"})
     * as a Spring Data derived query. Consumed by {@code OrderService.getOrderHistory}.</p>
     */
    List<Order> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    /**
     * Contract 3 (Spend): the SUM of a member's {@code CONFIRMED} order totals on/after a cutoff.
     *
     * <p>Backs {@code rest/InternalOrderResourceRESTService} endpoint
     * {@code GET /internal/members/{memberId}/spend?days=}, where {@code since = now() - days}.
     * Reimplements the 90-day spend sub-select of the {@code recalculate_customer_tiers} stored
     * procedure (db/02_stored_procedures.sql L295-326) in JPQL. {@code COALESCE(..., 0)} guarantees
     * a non-null result (returns {@code 0}/{@code ZERO} when the member has no qualifying orders).</p>
     */
    @Query("SELECT COALESCE(SUM(o.total), 0) FROM Order o "
            + "WHERE o.memberId = :memberId AND o.status = 'CONFIRMED' AND o.createdAt >= :since")
    BigDecimal sumConfirmedTotalSince(@Param("memberId") Long memberId,
                                      @Param("since") LocalDateTime since);
}
