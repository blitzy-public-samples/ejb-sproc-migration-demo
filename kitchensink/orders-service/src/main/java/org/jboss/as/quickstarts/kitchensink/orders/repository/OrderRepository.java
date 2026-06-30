package org.jboss.as.quickstarts.kitchensink.orders.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.jboss.as.quickstarts.kitchensink.orders.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    List<Order> findByMemberIdAndStatusAndCreatedAtAfter(Long memberId, String status, LocalDateTime cutoff);
}
