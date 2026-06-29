package org.jboss.as.quickstarts.kitchensink.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.data.OrderDraftItemRepository;
import org.jboss.as.quickstarts.kitchensink.data.OrderItemRepository;
import org.jboss.as.quickstarts.kitchensink.data.OrderRepository;
import org.jboss.as.quickstarts.kitchensink.data.ProductRepository;
import org.jboss.as.quickstarts.kitchensink.model.Member;
import org.jboss.as.quickstarts.kitchensink.model.Order;
import org.jboss.as.quickstarts.kitchensink.model.OrderDraftItem;
import org.jboss.as.quickstarts.kitchensink.model.OrderItem;
import org.jboss.as.quickstarts.kitchensink.model.Product;

/**
 * OrderService — orchestrates the full order lifecycle, re-implementing the {@code process_order}
 * PL/pgSQL stored procedure in Java.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): formerly a CDI
 * {@code @ApplicationScoped} bean whose {@code submitOrder()} delegated to a native
 * {@code SELECT process_order(...)} query while {@code previewOrder()} re-implemented the same
 * logic in Java — a documented dual-path maintenance hazard. It is now a Spring {@code @Service}
 * with a single private {@link #orchestrateOrder} that performs all read-only computation
 * (vendor selection, pricing, line totals, discount, shipping, totals). Both paths share it:
 * {@code previewOrder()} maps the result without persisting; {@code submitOrder()} is
 * {@code @Transactional} and additionally persists the order, persists order items, increments the
 * member's total spend, and clears the draft cart.</p>
 *
 * <p>Side-effect preservation: {@code orchestrateOrder} invokes {@link DiscountService#calculateDiscount}
 * which writes exactly one {@code discount_audit} row — preserving today's behavior where both the
 * preview path and the submit path each write one audit row (never dropped from preview, never
 * double-counted in submit).</p>
 */
@Service
public class OrderService {

    private final PricingService pricingService;
    private final VendorSelectionService vendorSelectionService;
    private final DiscountService discountService;
    private final ShippingService shippingService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final MemberRepository memberRepository;
    private final OrderDraftItemRepository orderDraftItemRepository;
    private final ProductRepository productRepository;

    public OrderService(PricingService pricingService,
                        VendorSelectionService vendorSelectionService,
                        DiscountService discountService,
                        ShippingService shippingService,
                        OrderRepository orderRepository,
                        OrderItemRepository orderItemRepository,
                        MemberRepository memberRepository,
                        OrderDraftItemRepository orderDraftItemRepository,
                        ProductRepository productRepository) {
        this.pricingService = pricingService;
        this.vendorSelectionService = vendorSelectionService;
        this.discountService = discountService;
        this.shippingService = shippingService;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.memberRepository = memberRepository;
        this.orderDraftItemRepository = orderDraftItemRepository;
        this.productRepository = productRepository;
    }

    /**
     * Adds a product to the member's draft cart.
     *
     * @param memberId   the member ID
     * @param productId  the product ID
     * @param quantity   the quantity to add
     */
    @Transactional
    public void addToCart(Long memberId, Long productId, int quantity) {
        OrderDraftItem item = new OrderDraftItem();
        item.setMemberId(memberId);
        item.setProductId(productId);
        item.setQuantity(quantity);
        orderDraftItemRepository.save(item);
    }

    /**
     * Removes a product from the member's draft cart.
     *
     * @param memberId   the member ID
     * @param productId  the product ID to remove
     */
    @Transactional
    public void removeFromCart(Long memberId, Long productId) {
        orderDraftItemRepository.deleteByMemberIdAndProductId(memberId, productId);
    }

    /**
     * Performs all read-only order computation shared by {@link #previewOrder} and
     * {@link #submitOrder}: for each draft line it selects the best vendor, prices the line,
     * accumulates the subtotal and total weight, then applies the member discount (which writes
     * one {@code discount_audit} row) and shipping, and computes the final total.
     *
     * <p>This is the single source of truth that eliminates the former preview/submit drift.
     * It performs no persistence, no member update, and no draft clearing.</p>
     */
    private OrchestrationResult orchestrateOrder(Long memberId, String destinationZip, boolean expedite) {
        List<OrderDraftItem> draftItems = orderDraftItemRepository.findByMemberId(memberId);

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        List<LineItemPreview> lineItems = new ArrayList<>();

        for (OrderDraftItem draftItem : draftItems) {
            Long productId = draftItem.getProductId();
            int quantity = draftItem.getQuantity();

            Long vendorId = vendorSelectionService.selectBestVendor(productId, quantity);
            if (vendorId == null) {
                continue;
            }

            BigDecimal unitPrice = pricingService.calculatePrice(productId, vendorId, quantity);
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity))
                .setScale(2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(lineTotal);

            // Accumulate weight (weight_lbs may be null -> treated as 0), mirroring process_order
            // which joined products for weight_lbs and used COALESCE(weight_lbs, 0).
            Product product = productRepository.findById(productId).orElse(null);
            BigDecimal weightLbs = (product != null && product.getWeightLbs() != null)
                ? product.getWeightLbs() : BigDecimal.ZERO;
            totalWeight = totalWeight.add(weightLbs.multiply(BigDecimal.valueOf(quantity)));

            lineItems.add(new LineItemPreview(productId, vendorId, quantity, unitPrice, lineTotal));
        }

        BigDecimal discountAmount = discountService.calculateDiscount(memberId, subtotal);
        BigDecimal shippingCost = shippingService.calculateShipping(destinationZip, totalWeight, expedite);
        BigDecimal total = subtotal.subtract(discountAmount).add(shippingCost);

        return new OrchestrationResult(subtotal, discountAmount, shippingCost, total, lineItems);
    }

    /**
     * Previews the order total without committing it. Delegates entirely to the shared
     * {@link #orchestrateOrder} and maps the result to an {@link OrderPreview}; performs no
     * persistence.
     *
     * @param memberId        the member ID
     * @param destinationZip  the destination ZIP code
     * @param expedite        whether expedited shipping is requested
     * @return                an OrderPreview with full cost breakdown
     */
    public OrderPreview previewOrder(Long memberId, String destinationZip, boolean expedite) {
        OrchestrationResult r = orchestrateOrder(memberId, destinationZip, expedite);
        return new OrderPreview(r.subtotal, r.discountAmount, r.shippingCost, r.total, r.items);
    }

    /**
     * Submits the order: validates the member and cart, runs the shared orchestration, then
     * persists the {@code CONFIRMED} order and its items, increments the member's
     * {@code total_spend}, and clears the draft cart — all atomically.
     *
     * @param memberId        the member ID
     * @param destinationZip  the destination ZIP code
     * @param expedite        whether expedited shipping is requested
     * @return                the new order ID
     * @throws MemberNotFoundException if the member does not exist (≙ {@code P0003})
     * @throws EmptyCartException      if the member's draft cart is empty (≙ {@code P0004})
     */
    @Transactional
    public Long submitOrder(Long memberId, String destinationZip, boolean expedite) {
        // Validate member exists (≙ process_order P0003).
        if (!memberRepository.existsById(memberId)) {
            throw new MemberNotFoundException("Member not found: " + memberId);
        }
        // Validate cart is not empty (≙ process_order P0004).
        if (orderDraftItemRepository.findByMemberId(memberId).isEmpty()) {
            throw new EmptyCartException("Cart is empty for member " + memberId);
        }

        OrchestrationResult r = orchestrateOrder(memberId, destinationZip, expedite);

        // Persist the confirmed order.
        Order order = new Order();
        order.setMemberId(memberId);
        order.setStatus("CONFIRMED");
        order.setSubtotal(r.subtotal);
        order.setDiscountAmount(r.discountAmount);
        order.setShippingCost(r.shippingCost);
        order.setTotal(r.total);
        order.setCreatedAt(LocalDateTime.now());
        Order savedOrder = orderRepository.save(order);

        // Persist order items (entities use plain Long FKs with no cascade -> persist explicitly).
        for (LineItemPreview li : r.items) {
            OrderItem item = new OrderItem();
            item.setOrderId(savedOrder.getId());
            item.setProductId(li.getProductId());
            item.setVendorId(li.getVendorId());
            item.setQuantity(li.getQuantity());
            item.setUnitPrice(li.getUnitPrice());
            item.setLineTotal(li.getLineTotal());
            orderItemRepository.save(item);
        }

        // Increment member total_spend and stamp tier_updated_at.
        Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new MemberNotFoundException("Member not found: " + memberId));
        BigDecimal currentSpend = (member.getTotalSpend() != null) ? member.getTotalSpend() : BigDecimal.ZERO;
        member.setTotalSpend(currentSpend.add(r.total));
        member.setTierUpdatedAt(LocalDateTime.now());
        memberRepository.save(member);

        // Clear the draft cart.
        orderDraftItemRepository.deleteByMemberId(memberId);

        return savedOrder.getId();
    }

    /**
     * Returns a single order by ID, or {@code null} if not found (the REST layer maps the null
     * case to HTTP 404).
     *
     * @param orderId  the order ID
     * @return         the Order entity, or {@code null}
     */
    public Order getOrder(Long orderId) {
        return orderRepository.findById(orderId).orElse(null);
    }

    /**
     * Returns the order history for a member, sorted by creation date descending.
     *
     * @param memberId  the member ID
     * @return          list of orders sorted by creation date descending
     */
    public List<Order> getOrderHistory(Long memberId) {
        return orderRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    // -----------------------------------------------------------------------
    // Result + view types
    // -----------------------------------------------------------------------

    /** Immutable carrier for the computed values produced by {@link #orchestrateOrder}. */
    private static final class OrchestrationResult {
        private final BigDecimal subtotal;
        private final BigDecimal discountAmount;
        private final BigDecimal shippingCost;
        private final BigDecimal total;
        private final List<LineItemPreview> items;

        private OrchestrationResult(BigDecimal subtotal, BigDecimal discountAmount,
                BigDecimal shippingCost, BigDecimal total, List<LineItemPreview> items) {
            this.subtotal = subtotal;
            this.discountAmount = discountAmount;
            this.shippingCost = shippingCost;
            this.total = total;
            this.items = items;
        }
    }

    public static class OrderPreview {
        private final BigDecimal subtotal;
        private final BigDecimal discountAmount;
        private final BigDecimal shippingCost;
        private final BigDecimal total;
        private final List<LineItemPreview> items;

        public OrderPreview(BigDecimal subtotal, BigDecimal discountAmount,
                BigDecimal shippingCost, BigDecimal total, List<LineItemPreview> items) {
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
