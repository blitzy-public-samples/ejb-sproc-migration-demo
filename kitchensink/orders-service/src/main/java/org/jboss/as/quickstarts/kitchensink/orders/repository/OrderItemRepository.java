package org.jboss.as.quickstarts.kitchensink.orders.repository;

import java.util.List;
import org.jboss.as.quickstarts.kitchensink.orders.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);
}
