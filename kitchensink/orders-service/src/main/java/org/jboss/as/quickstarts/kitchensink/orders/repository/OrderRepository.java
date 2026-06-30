package org.jboss.as.quickstarts.kitchensink.orders.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.jboss.as.quickstarts.kitchensink.orders.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    /**
     * CONFIRMED orders for a member at or after the given cutoff. The comparison is INCLUSIVE
     * ({@code created_at >= cutoff}) to match the stored-procedure / AAP spend window
     * ({@code created_at >= NOW() - INTERVAL '90 days'}, db/02_stored_procedures.sql): an order
     * placed exactly at the boundary instant must still be counted. (The earlier
     * {@code ...CreatedAtAfter} form was strict {@code >} and incorrectly excluded the boundary.)
     */
    List<Order> findByMemberIdAndStatusAndCreatedAtGreaterThanEqual(Long memberId, String status, LocalDateTime cutoff);
}
