package org.jboss.as.quickstarts.kitchensink.orders.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.jboss.as.quickstarts.kitchensink.orders.model.Order;
import org.jboss.as.quickstarts.kitchensink.orders.model.OrderItem;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderDraftItemRepository;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderItemRepository;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderRepository;

/**
 * Dedicated transactional collaborator that owns the single local persistence boundary of an
 * order submission: persist the CONFIRMED {@link Order}, persist its {@link OrderItem} rows, and
 * clear the member's draft cart — all committed together as one atomic unit.
 *
 * <p><strong>Why this is a separate bean (not a method on {@code OrderService}).</strong>
 * {@code submitOrder()} is deliberately NOT {@code @Transactional} so that the cross-service HTTP
 * performed during {@code orchestrateOrder()} runs OUTSIDE any transaction (the transaction-vs-HTTP
 * ordering rule, AAP &sect;0.7.2). The persistence step, however, MUST run inside a transaction.
 * Previously {@code OrderService} satisfied this by self-injecting its own Spring proxy
 * ({@code @Autowired @Lazy private OrderService self}) and calling {@code self.persistConfirmedOrder(...)}
 * — a field self-injection that violates the constructor-injection-only rule and creates a circular
 * self-proxy. Extracting the transactional work into THIS constructor-injected collaborator removes
 * the self-proxy entirely: {@code OrderService} simply calls
 * {@code orderPersistenceService.persistConfirmedOrder(...)} as an ordinary cross-bean invocation, so
 * the {@code @Transactional} proxy is applied correctly and no self-injection is needed.</p>
 *
 * <p>No cross-service HTTP occurs here; all marketplace/users calls complete during the calculation
 * phase in {@code OrderService.orchestrateOrder()} before this method is invoked.</p>
 */
@Service
public class OrderPersistenceService {

    // Constructor injection (single constructor -> no @Autowired required).
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderDraftItemRepository orderDraftItemRepository;

    public OrderPersistenceService(OrderRepository orderRepository,
                                   OrderItemRepository orderItemRepository,
                                   OrderDraftItemRepository orderDraftItemRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderDraftItemRepository = orderDraftItemRepository;
    }

    /**
     * Single local {@code @Transactional} boundary: persist the CONFIRMED Order, persist its
     * OrderItem rows, and clear the member's draft cart. The order, its items, and the derived
     * {@code deleteByMemberId()} cart-clear all commit together. The cart-clear in particular
     * REQUIRES this ambient transaction: a derived delete query (SELECT + em.remove) is not
     * self-transactional the way {@code SimpleJpaRepository.save()} is, so without the boundary it
     * would throw {@code TransactionRequiredException}.
     *
     * @param memberId the owning member
     * @param preview  the fully computed order preview produced by {@code OrderService.orchestrateOrder()}
     * @return the generated id of the persisted CONFIRMED order
     */
    @Transactional
    public Long persistConfirmedOrder(Long memberId, OrderService.OrderPreview preview) {
        Order order = new Order();
        order.setMemberId(memberId);
        order.setStatus("CONFIRMED");
        order.setSubtotal(preview.getSubtotal());
        order.setDiscountAmount(preview.getDiscountAmount());
        order.setShippingCost(preview.getShippingCost());
        order.setTotal(preview.getTotal());
        order.setCreatedAt(LocalDateTime.now());
        Order saved = orderRepository.save(order);

        for (OrderService.LineItemPreview line : preview.getItems()) {
            OrderItem item = new OrderItem();
            item.setOrderId(saved.getId());
            item.setProductId(line.getProductId());
            item.setVendorId(line.getVendorId());
            item.setQuantity(line.getQuantity());
            item.setUnitPrice(line.getUnitPrice());
            item.setLineTotal(line.getLineTotal());
            orderItemRepository.save(item);
        }

        // Clear the draft cart (DELETE FROM order_draft_items WHERE member_id = ...) (SQL L284).
        orderDraftItemRepository.deleteByMemberId(memberId);

        return saved.getId();
    }
}
