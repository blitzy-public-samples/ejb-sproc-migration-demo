package org.jboss.as.quickstarts.kitchensink.orders.repository;

import java.util.List;

import org.jboss.as.quickstarts.kitchensink.orders.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link OrderItem} (the {@code order_items} table), owned by orders-service.
 *
 * <p>CRUD (including {@code save} used when persisting per-line items during order orchestration)
 * is inherited from {@link JpaRepository}. The single derived finder below replaces the monolith
 * {@code OrderRepository.findItemsByOrderId} JPQL.</p>
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /**
     * All line items belonging to a given order.
     *
     * <p>Replaces the monolith {@code OrderRepository.findItemsByOrderId} query
     * ({@code "SELECT oi FROM OrderItem oi WHERE oi.orderId = :orderId"}) as a Spring Data
     * derived query. Consumed by {@code OrderService} when reading an order's line items.</p>
     */
    List<OrderItem> findByOrderId(Long orderId);
}
