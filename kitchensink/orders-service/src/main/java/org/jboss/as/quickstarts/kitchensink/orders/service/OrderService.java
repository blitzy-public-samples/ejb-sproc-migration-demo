package org.jboss.as.quickstarts.kitchensink.orders.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.jboss.as.quickstarts.kitchensink.orders.client.MarketplaceClient;
import org.jboss.as.quickstarts.kitchensink.orders.client.UsersClient;
import org.jboss.as.quickstarts.kitchensink.orders.exception.NoEligibleVendorException;
import org.jboss.as.quickstarts.kitchensink.orders.model.Order;
import org.jboss.as.quickstarts.kitchensink.orders.model.OrderDraftItem;
import org.jboss.as.quickstarts.kitchensink.orders.model.OrderItem;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderDraftItemRepository;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderItemRepository;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the orders domain. Pure-Java reimplementation of the process_order
 * PL/pgSQL stored procedure (db/02_stored_procedures.sql L196-290); no native query
 * is ever executed. Pricing/vendor selection and member tier are obtained over HTTP
 * via MarketplaceClient/UsersClient -- never by importing marketplace-/users-service
 * classes (cross-domain boundary rule).
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderDraftItemRepository orderDraftItemRepository;
    private final MarketplaceClient marketplaceClient;
    private final UsersClient usersClient;
    private final DiscountService discountService;
    private final ShippingService shippingService;

    public OrderService(OrderRepository orderRepository,
                        OrderItemRepository orderItemRepository,
                        OrderDraftItemRepository orderDraftItemRepository,
                        MarketplaceClient marketplaceClient,
                        UsersClient usersClient,
                        DiscountService discountService,
                        ShippingService shippingService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderDraftItemRepository = orderDraftItemRepository;
        this.marketplaceClient = marketplaceClient;
        this.usersClient = usersClient;
        this.discountService = discountService;
        this.shippingService = shippingService;
    }

    @Transactional
    public void addToCart(Long memberId, Long productId, int quantity) {
        OrderDraftItem item = new OrderDraftItem();
        item.setMemberId(memberId);
        item.setProductId(productId);
        item.setQuantity(quantity);
        orderDraftItemRepository.save(item);
    }

    @Transactional
    public void removeFromCart(Long memberId, Long productId) {
        orderDraftItemRepository.deleteByMemberIdAndProductId(memberId, productId);
    }

    /** Non-transactional preview; shares orchestrateOrder with submit for dual-path parity. */
    public OrderPreview previewOrder(Long memberId, String destinationZip, boolean expedite) {
        List<OrderDraftItem> draftItems = orderDraftItemRepository.findByMemberId(memberId);
        return orchestrateOrder(memberId, draftItems, destinationZip, expedite);
    }

    @Transactional
    public Long submitOrder(Long memberId, String destinationZip, boolean expedite) {
        // 1. Validate member exists (replaces process_order P0003). UsersClient maps 404 -> MemberNotFoundException.
        //    Done first so a missing member fails before the empty-cart check, preserving proc ordering.
        usersClient.getMemberTier(memberId);

        // 2. Load cart; reject empty (replaces process_order P0004).
        List<OrderDraftItem> draftItems = orderDraftItemRepository.findByMemberId(memberId);
        if (draftItems.isEmpty()) {
            throw new IllegalStateException("Cart is empty for member " + memberId);
        }

        // 3. Compute via the shared routine (all HTTP reads happen here, before any persistence,
        //    so a ServiceUnavailableException rolls back an empty transaction cleanly).
        OrderPreview computed = orchestrateOrder(memberId, draftItems, destinationZip, expedite);

        // 4. Persist the CONFIRMED order. Order has no @PrePersist, so set createdAt explicitly.
        Order order = new Order();
        order.setMemberId(memberId);
        order.setStatus("CONFIRMED");
        order.setSubtotal(computed.getSubtotal());
        order.setDiscountAmount(computed.getDiscountAmount());
        order.setShippingCost(computed.getShippingCost());
        order.setTotal(computed.getTotal());
        order.setCreatedAt(LocalDateTime.now());
        Order savedOrder = orderRepository.save(order);
        Long orderId = savedOrder.getId();

        // 5. Persist one order_items row per computed line.
        for (LineItemPreview line : computed.getItems()) {
            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(orderId);
            orderItem.setProductId(line.getProductId());
            orderItem.setVendorId(line.getVendorId());
            orderItem.setQuantity(line.getQuantity());
            orderItem.setUnitPrice(line.getUnitPrice());
            orderItem.setLineTotal(line.getLineTotal());
            orderItemRepository.save(orderItem);
        }

        // 6. Clear the draft cart (process_order: DELETE FROM order_draft_items WHERE member_id = ...).
        orderDraftItemRepository.deleteByMemberId(memberId);

        // 7. Increment the member's lifetime total_spend by the order SUBTOTAL (review C4 / AAP 0.6.1:
        //    Source A overrides the Source-B SQL, which added the total). The `member` table is owned by
        //    users-service and the boundary rule (AAP 0.7.2) forbids orders-service from writing it
        //    directly, so the increment is performed over HTTP through UsersClient against the
        //    users-service internal write contract (POST /internal/members/{id}/total-spend). This is the
        //    LAST step of the @Transactional submit: a MemberNotFoundException (404) or
        //    ServiceUnavailableException (5xx/unreachable) propagates and rolls the whole order back, so
        //    the order row and total_spend can never diverge. It is intentionally NOT part of
        //    orchestrateOrder, so the non-transactional preview path never writes spend.
        usersClient.incrementMemberTotalSpend(memberId, computed.getSubtotal());

        return orderId;
    }

    public Order getOrder(Long orderId) {
        return orderRepository.findById(orderId).orElse(null);
    }

    public List<Order> getOrderHistory(Long memberId) {
        return orderRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    /**
     * Single shared compute routine used by both previewOrder and submitOrder. Guarantees the
     * dual-path parity invariant: subtotal/discount/shipping/total can never diverge between
     * the two paths (AAP 0.6.3). Faithful to process_order (db/02_stored_procedures.sql L196-290).
     */
    private OrderPreview orchestrateOrder(Long memberId, List<OrderDraftItem> draftItems,
                                          String destinationZip, boolean expedite) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        List<LineItemPreview> lineItems = new ArrayList<>();

        for (OrderDraftItem draftItem : draftItems) {
            Long productId = draftItem.getProductId();
            int quantity = draftItem.getQuantity();

            // select_best_vendor (over HTTP) via the AUTHORITATIVE Source-A /best-vendor endpoint.
            // A null selection means no eligible/in-stock vendor for this line: abort the entire order
            // (faithful to process_order, which failed the order rather than dropping the line) instead
            // of silently skipping it and persisting a partial, under-charged order (review M1).
            Long vendorId = marketplaceClient.selectBestVendor(productId, quantity);
            if (vendorId == null) {
                throw new NoEligibleVendorException(
                        "No eligible vendor for product " + productId + " at quantity " + quantity);
            }

            // calculate_price (over HTTP). line_total = ROUND(unit_price * qty, 2) in BOTH paths (proc L251)
            // so preview and submit cannot drift.
            BigDecimal unitPrice = marketplaceClient.getPrice(productId, vendorId, quantity);
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity))
                    .setScale(2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(lineTotal);

            // Accumulate cart weight for shipping (review C5). process_order summed
            // COALESCE(weight_lbs, 0) * quantity by joining products; orders-service owns no Product
            // entity (boundary rule, AAP 0.7.2) so it reads the per-product weight over HTTP. A
            // null/absent weight contributes 0 (MarketplaceClient maps 404/null weight -> ZERO). No
            // rounding here: weight is accumulated at full precision and ShippingService performs the
            // single ROUND(...,2), faithful to the procedure which summed weight then rounded once.
            BigDecimal productWeight = marketplaceClient.getProductWeight(productId);
            if (productWeight == null) {
                productWeight = BigDecimal.ZERO;
            }
            totalWeight = totalWeight.add(productWeight.multiply(BigDecimal.valueOf(quantity)));

            lineItems.add(new LineItemPreview(productId, vendorId, quantity, unitPrice, lineTotal));
        }

        BigDecimal discountAmount = discountService.calculateDiscount(memberId, subtotal);
        BigDecimal shippingCost = shippingService.calculateShipping(destinationZip, totalWeight, expedite);
        BigDecimal total = subtotal.subtract(discountAmount).add(shippingCost);

        return new OrderPreview(subtotal, discountAmount, shippingCost, total, lineItems);
    }

    /** Immutable preview/response value object (REST contract shape) -- preserved from the monolith. */
    public static class OrderPreview {
        private final BigDecimal subtotal;
        private final BigDecimal discountAmount;
        private final BigDecimal shippingCost;
        private final BigDecimal total;
        private final List<LineItemPreview> items;

        public OrderPreview(BigDecimal subtotal, BigDecimal discountAmount, BigDecimal shippingCost,
                            BigDecimal total, List<LineItemPreview> items) {
            this.subtotal = subtotal;
            this.discountAmount = discountAmount;
            this.shippingCost = shippingCost;
            this.total = total;
            this.items = items;
        }

        public BigDecimal getSubtotal() { return subtotal; }
        public BigDecimal getDiscountAmount() { return discountAmount; }
        public BigDecimal getShippingCost() { return shippingCost; }
        public BigDecimal getTotal() { return total; }
        public List<LineItemPreview> getItems() { return items; }
    }

    /** Immutable per-line preview value object -- preserved from the monolith. */
    public static class LineItemPreview {
        private final Long productId;
        private final Long vendorId;
        private final int quantity;
        private final BigDecimal unitPrice;
        private final BigDecimal lineTotal;

        public LineItemPreview(Long productId, Long vendorId, int quantity,
                               BigDecimal unitPrice, BigDecimal lineTotal) {
            this.productId = productId;
            this.vendorId = vendorId;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.lineTotal = lineTotal;
        }

        public Long getProductId() { return productId; }
        public Long getVendorId() { return vendorId; }
        public int getQuantity() { return quantity; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public BigDecimal getLineTotal() { return lineTotal; }
    }
}
