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
 * OrderItemRepository (findByOrderId). The sumConfirmedTotalsByMemberSince(...) grouped aggregate is
 * NEW: it mirrors the COALESCE(SUM(o.total),0) over CONFIRMED orders within the last 90 days that the
 * recalculate_customer_tiers stored procedure performed (db/02_stored_procedures.sql L304-308), but
 * for ALL members in a single grouped query rather than one query per member.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    // Was findByMemberId(...) via JPQL "... WHERE o.memberId = :memberId ORDER BY o.createdAt DESC".
    List<Order> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    /**
     * Grouped 90-day CONFIRMED spend for ALL members, computed in a SINGLE query.
     *
     * <p>Replaces the former per-member {@code sumConfirmedTotalSince(memberId, cutoff)} that
     * {@code TierRecalculationService} invoked once per member (an N+1 aggregate pattern). This
     * grouped form returns one row per member that HAS at least one qualifying CONFIRMED order in the
     * window; members with no qualifying orders are simply absent from the result and are defaulted to
     * zero in memory by the service (≙ the stored procedure's {@code COALESCE(SUM(total), 0)}).</p>
     *
     * <p>{@code SUM} is used without {@code COALESCE} here because, within a {@code GROUP BY} group,
     * at least one row always exists so the sum is non-null; the unambiguous {@link java.math.BigDecimal}
     * result type also keeps the interface projection mapping robust. The service still applies a
     * defensive null/absent guard. {@code TierRecalculationService} passes
     * {@code cutoff = LocalDateTime.now().minusDays(90)}.</p>
     *
     * @param cutoff the inclusive lower bound on {@code created_at} (now minus 90 days)
     * @return one {@link MemberSpendProjection} per member with qualifying CONFIRMED orders in the window
     */
    @Query("SELECT o.memberId AS memberId, SUM(o.total) AS totalSpend "
         + "FROM Order o "
         + "WHERE o.status = 'CONFIRMED' AND o.createdAt >= :cutoff "
         + "GROUP BY o.memberId")
    List<MemberSpendProjection> sumConfirmedTotalsByMemberSince(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Spring Data interface projection for the grouped 90-day CONFIRMED spend aggregate. The query's
     * {@code memberId}/{@code totalSpend} aliases map to these getters.
     */
    interface MemberSpendProjection {
        Long getMemberId();
        BigDecimal getTotalSpend();
    }
}
