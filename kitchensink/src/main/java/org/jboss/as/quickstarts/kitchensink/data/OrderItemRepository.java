package org.jboss.as.quickstarts.kitchensink.data;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import org.jboss.as.quickstarts.kitchensink.model.OrderItem;

/**
 * Spring Data JPA repository for {@link OrderItem}.
 *
 * New in the Spring Boot migration: holds the items-by-order query previously found in
 * OrderRepository.findItemsByOrderId(...) (Jakarta EE CDI + EntityManager JPQL). Required as a
 * first-class repository because OrderItem uses plain Long foreign keys with NO JPA cascade, so
 * OrderService.submitOrder persists each row explicitly via inherited save(...)/saveAll(...).
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    // Relocated from OrderRepository.findItemsByOrderId — derived query on the orderId field.
    List<OrderItem> findByOrderId(Long orderId);
}
