package org.jboss.as.quickstarts.kitchensink.orders.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.jboss.as.quickstarts.kitchensink.orders.client.MarketplaceClient;
import org.jboss.as.quickstarts.kitchensink.orders.client.ProductQuoteDto;
import org.jboss.as.quickstarts.kitchensink.orders.client.UsersClient;
import org.jboss.as.quickstarts.kitchensink.orders.model.Order;
import org.jboss.as.quickstarts.kitchensink.orders.model.OrderDraftItem;
import org.jboss.as.quickstarts.kitchensink.orders.model.OrderItem;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderDraftItemRepository;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderItemRepository;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderRepository;

/**
 * Faithful Java extraction of the process_order() PL/pgSQL stored procedure
 * (db/02_stored_procedures.sql, lines 196-290). The stored procedure is retained as reference
 * documentation only and is no longer invoked (zero native queries).
 *
 * The historical dual-path hazard -- a Java previewOrder() that mirrored process_order() while
 * submitOrder() delegated to the procedure -- is eliminated here: BOTH previewOrder() and
 * submitOrder() flow through the single private orchestrateOrder() method (extract-method).
 *
 * Cross-domain rule: pricing/quotes come from marketplace-service and the member tier from
 * users-service, both over HTTP via thin clients. ALL cross-service HTTP happens during the
 * calculation/preview phase, BEFORE any @Transactional boundary opens.
 */
@Service
public class OrderService {

    // Constructor injection (single constructor -> no @Autowired required).
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

    // ----- cart operations -----

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

    // ----- order orchestration (single shared source of truth) -----

    /**
     * Reproduces the calculation core of process_order (db/02_stored_procedures.sql L196-290):
     * for each draft item resolve a marketplace quote {vendorId, unitPrice, weightLbs},
     * lineTotal = ROUND(unitPrice * qty, 2), accumulate subtotal and total weight; then apply the
     * customer discount and shipping; total = subtotal - discount + shipping.
     *
     * ALL cross-service HTTP (marketplace quotes + users tier, the latter inside DiscountService)
     * occurs HERE, before any transaction is opened.
     */
    private OrderPreview orchestrateOrder(Long memberId, String destinationZip, boolean expedite) {
        List<OrderDraftItem> draftItems = orderDraftItemRepository.findByMemberId(memberId);

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        List<LineItemPreview> items = new ArrayList<>();

        for (OrderDraftItem draft : draftItems) {
            int quantity = draft.getQuantity();
            // GAP-1 (best vendor) + GAP-2 (product weight) resolved in one round-trip via the quote.
            ProductQuoteDto quote = marketplaceClient.getQuote(draft.getProductId(), quantity);
            Long vendorId = quote.vendorId();
            BigDecimal unitPrice = quote.unitPrice();

            // v_line_total := ROUND(v_unit_price * v_quantity, 2) (SQL L236).
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(lineTotal);

            // v_item_weight := COALESCE(weight_lbs, 0) * v_quantity; v_total_weight += v_item_weight (SQL L244-248).
            BigDecimal weightLbs = quote.weightLbs() == null ? BigDecimal.ZERO : quote.weightLbs();
            totalWeight = totalWeight.add(weightLbs.multiply(BigDecimal.valueOf(quantity)));

            items.add(new LineItemPreview(draft.getProductId(), vendorId, quantity, unitPrice, lineTotal));
        }

        // v_discount_amt := apply_customer_discount(p_member_id, v_subtotal) (SQL L262).
        BigDecimal discountAmount = discountService.calculateDiscount(memberId, subtotal);
        // v_shipping_cost := calculate_shipping(p_destination_zip, v_total_weight, p_expedite) (SQL L265).
        BigDecimal shippingCost = shippingService.calculateShipping(destinationZip, totalWeight, expedite);
        // v_total := v_subtotal - v_discount_amt + v_shipping_cost (SQL L268).
        BigDecimal total = subtotal.subtract(discountAmount).add(shippingCost);

        return new OrderPreview(subtotal, discountAmount, shippingCost, total, items);
    }

    /**
     * Non-transactional read-only preview. Runs orchestrateOrder() (all cross-service HTTP) and
     * returns the computed OrderPreview without persisting anything.
     */
    public OrderPreview previewOrder(Long memberId, String destinationZip, boolean expedite) {
        return orchestrateOrder(memberId, destinationZip, expedite);
    }

    /**
     * Orchestrates the order (all cross-service HTTP completes FIRST, outside any transaction),
     * then persists the CONFIRMED order + items and clears the draft cart inside a single local
     * @Transactional boundary, and finally -- AFTER commit -- triggers the member-spend increment.
     */
    public Long submitOrder(Long memberId, String destinationZip, boolean expedite) {
        // Phase 1: calculation/preview. All marketplace + users HTTP happens here, BEFORE the tx.
        OrderPreview preview = orchestrateOrder(memberId, destinationZip, expedite);

        // Phase 2: single local transactional persist (order + items + cart clear).
        Long orderId = persistConfirmedOrder(memberId, preview);

        // Phase 3: GAP-3 (§0.6.6) member total_spend increment. Intentionally POST-COMMIT and
        // outside the orders transaction: orders-service must not write the member table, and the
        // call crosses a service boundary over HTTP. It is therefore eventually consistent -- the
        // order can commit even if this increment fails, and the nightly tier recalculation in
        // users-service re-derives spend from CONFIRMED order history as the backstop. The call
        // MUST occur because OrderServiceIT asserts total_spend increases on submit.
        usersClient.incrementMemberSpend(memberId, preview.getTotal());

        return orderId;
    }

    /**
     * Single local @Transactional boundary: persist the CONFIRMED Order, persist its OrderItem
     * rows, and clear the member's draft cart. No cross-service HTTP occurs inside this method.
     *
     * NOTE (Spring proxying): submitOrder() calls this method via 'this', so the @Transactional
     * proxy does not wrap it; however every Spring Data repository write is itself transactional,
     * so the order, its items, and the cart-clear each commit. submitOrder() is deliberately NOT
     * annotated @Transactional so that no transaction is open while the cross-service HTTP calls
     * in orchestrateOrder() run (the transaction-vs-HTTP ordering rule, §0.7.2).
     */
    @Transactional
    public Long persistConfirmedOrder(Long memberId, OrderPreview preview) {
        Order order = new Order();
        order.setMemberId(memberId);
        order.setStatus("CONFIRMED");
        order.setSubtotal(preview.getSubtotal());
        order.setDiscountAmount(preview.getDiscountAmount());
        order.setShippingCost(preview.getShippingCost());
        order.setTotal(preview.getTotal());
        order.setCreatedAt(LocalDateTime.now());
        Order saved = orderRepository.save(order);

        for (LineItemPreview line : preview.getItems()) {
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

    // ----- queries -----

    public Order getOrder(Long orderId) {
        return orderRepository.findById(orderId).orElse(null);
    }

    public List<Order> getOrderHistory(Long memberId) {
        return orderRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    /**
     * Producer support for Contract 3 (GET /internal/members/{id}/spend?days=): sum of total for
     * the member's CONFIRMED orders since (now - days). Computed in Java over a derived-query load
     * (never a native query).
     */
    public BigDecimal computeMemberSpend(Long memberId, int days) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<Order> orders = orderRepository.findByMemberIdAndStatusAndCreatedAtAfter(memberId, "CONFIRMED", cutoff);
        BigDecimal sum = BigDecimal.ZERO;
        for (Order order : orders) {
            if (order.getTotal() != null) {
                sum = sum.add(order.getTotal());
            }
        }
        return sum;
    }

    // ----- nested view models (carried over verbatim from the source OrderService) -----

    public static class OrderPreview {
        private final BigDecimal subtotal;
        private final BigDecimal discountAmount;
        private final BigDecimal shippingCost;
        private final BigDecimal total;
        private final List<LineItemPreview> items;
        public OrderPreview(BigDecimal subtotal, BigDecimal discountAmount,
                BigDecimal shippingCost, BigDecimal total, List<LineItemPreview> items) {
            this.subtotal = subtotal; this.discountAmount = discountAmount;
            this.shippingCost = shippingCost; this.total = total; this.items = items;
        }
        public BigDecimal getSubtotal() { return subtotal; }
        public BigDecimal getDiscountAmount() { return discountAmount; }
        public BigDecimal getShippingCost() { return shippingCost; }
        public BigDecimal getTotal() { return total; }
        public List<LineItemPreview> getItems() { return items; }
    }

    public static class LineItemPreview {
        private final Long productId;
        private final Long vendorId;
        private final int quantity;
        private final BigDecimal unitPrice;
        private final BigDecimal lineTotal;
        public LineItemPreview(Long productId, Long vendorId, int quantity,
                BigDecimal unitPrice, BigDecimal lineTotal) {
            this.productId = productId; this.vendorId = vendorId; this.quantity = quantity;
            this.unitPrice = unitPrice; this.lineTotal = lineTotal;
        }
        public Long getProductId() { return productId; }
        public Long getVendorId() { return vendorId; }
        public int getQuantity() { return quantity; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public BigDecimal getLineTotal() { return lineTotal; }
    }
}
