package org.jboss.as.quickstarts.kitchensink.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * OrderService — embodies the process_order stored procedure (db/02_stored_procedures.sql L196-290).
 *
 * Migrated from CDI @ApplicationScoped to Spring @Service. The native "SELECT process_order(...)" call is
 * removed: submit is fully reimplemented in Java. previewOrder() and submitOrder() now share one private
 * orchestrateOrder() that performs ALL read-only computation (vendor selection, pricing, line totals,
 * discount, shipping, totals); they differ only in persistence (AAP §0.6.4). EntityManager is gone — drafts
 * use OrderDraftItemRepository. Dependency graph is acyclic (PricingService is a stateless shared leaf).
 *
 * MIGRATION NOTES (JBoss EAP 8 / Jakarta EE 10 -> Spring Boot 3.x):
 * <ul>
 *   <li>CDI {@code @ApplicationScoped} -> Spring {@code @Service}; CDI {@code @Inject} field injection ->
 *       constructor injection of nine immutable {@code final} collaborators (no {@code @Autowired} needed
 *       for a single constructor).</li>
 *   <li>{@code jakarta.transaction.Transactional} -> {@code org.springframework.transaction.annotation.Transactional},
 *       declared on the write methods ({@code addToCart}, {@code removeFromCart}, {@code submitOrder}).
 *       {@code previewOrder} is intentionally NOT transactional so its single {@code discount_audit} write
 *       commits independently, mirroring the legacy behavior; the private {@code orchestrateOrder} is not
 *       annotated either (it carries no transactional boundary of its own).</li>
 *   <li>{@code EntityManager} (persist / JPQL / native {@code SELECT process_order(...)}) -> Spring Data
 *       repositories. There is NO {@code EntityManager}, NO JPQL, and NO stored-procedure invocation: the
 *       full ordering pipeline now lives in Java.</li>
 *   <li>Preview/submit dual-path unification (AAP §0.6.4): the former hazard where {@code previewOrder()}
 *       re-implemented the logic while {@code submitOrder()} delegated to {@code process_order} is removed.
 *       A single {@code orchestrateOrder} is the source of truth; {@code submitOrder} adds persistence.</li>
 * </ul>
 */
@Service
public class OrderService {

    private final VendorSelectionService vendorSelectionService;
    private final DiscountService discountService;
    private final ShippingService shippingService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final MemberRepository memberRepository;
    private final OrderDraftItemRepository orderDraftItemRepository;
    private final ProductRepository productRepository;

    /**
     * Constructor injection of the eight collaborators (replaces CDI {@code @Inject} field injection).
     * A single constructor is wired by Spring automatically, so no {@code @Autowired} is required.
     *
     * <p>{@code OrderService} no longer injects {@code PricingService} directly: after the
     * order-orchestration N+1 fix, {@link VendorSelectionService#selectBestVendorWithPrice} returns the
     * winning vendor together with its already-computed unit price, so the order pipeline never prices a
     * line itself. {@link PricingService} remains a stateless shared leaf (AAP §0.6.3), now shared by
     * {@link VendorSelectionService} and {@link DiscountService}; the dependency graph stays acyclic.</p>
     */
    public OrderService(VendorSelectionService vendorSelectionService,
                        DiscountService discountService,
                        ShippingService shippingService,
                        OrderRepository orderRepository,
                        OrderItemRepository orderItemRepository,
                        MemberRepository memberRepository,
                        OrderDraftItemRepository orderDraftItemRepository,
                        ProductRepository productRepository) {
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
        // SECURITY / input validation (F4): verify the referenced member and product EXIST before
        // inserting a draft row. The model uses plain Long foreign keys (no JPA associations) and
        // order_draft_items is FK-constrained to members(id)/products(id); inserting an unknown id would
        // otherwise fail deep in the database and surface as an opaque HTTP 500. Surfacing the not-found
        // condition here as MemberNotFoundException / InventoryNotFoundException yields a clean 404 via
        // RestExceptionHandler. existsById is a cheap existence probe (no entity hydration). Centralizing
        // the check in the service also protects any non-REST caller, not just the controller.
        if (!memberRepository.existsById(memberId)) {
            throw new MemberNotFoundException("Member not found: " + memberId);
        }
        if (!productRepository.existsById(productId)) {
            throw new InventoryNotFoundException("Product not found: " + productId);
        }

        // Preserve current behavior: insert a new draft row (no upsert). em.persist -> repository.save.
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
     * Previews the order total without committing it. Delegates entirely to the shared
     * {@link #orchestrateOrder}; performs NO {@code Order}/{@code OrderItem} persistence, NO member
     * update, and NO draft clearing.
     *
     * <p>Not {@code @Transactional}: the single {@code discount_audit} write still happens (via
     * {@code orchestrateOrder} -> {@link DiscountService#calculateDiscount}) and commits independently,
     * preserving today's behavior.</p>
     *
     * @param memberId        the member ID
     * @param destinationZip  the destination ZIP code
     * @param expedite        whether expedited shipping is requested
     * @return                an {@link OrderPreview} with the full cost breakdown
     */
    public OrderPreview previewOrder(Long memberId, String destinationZip, boolean expedite) {
        return orchestrateOrder(memberId, destinationZip, expedite);
    }

    /**
     * Submits the order: runs the shared orchestration, then persists the {@code CONFIRMED} order and its
     * items, increments the member's {@code total_spend} (and stamps {@code tier_updated_at}), and clears
     * the draft cart — all atomically. Does NOT call any stored procedure and does NOT write a second
     * audit row (the single audit write happens inside {@link #orchestrateOrder}).
     *
     * @param memberId        the member ID
     * @param destinationZip  the destination ZIP code
     * @param expedite        whether expedited shipping is requested
     * @return                the new order ID
     * @throws MemberNotFoundException if the member does not exist (≙ {@code process_order} ERRCODE P0003)
     * @throws EmptyCartException      if the member's draft cart is empty (≙ {@code process_order} ERRCODE P0004)
     */
    @Transactional
    public Long submitOrder(Long memberId, String destinationZip, boolean expedite) {
        OrderPreview computed = orchestrateOrder(memberId, destinationZip, expedite);

        // Persist the CONFIRMED order.
        Order order = new Order();
        order.setMemberId(memberId);
        order.setStatus("CONFIRMED");
        order.setSubtotal(computed.getSubtotal());
        order.setDiscountAmount(computed.getDiscountAmount());
        order.setShippingCost(computed.getShippingCost());
        order.setTotal(computed.getTotal());
        order.setCreatedAt(LocalDateTime.now());
        Order saved = orderRepository.save(order);
        Long orderId = saved.getId();

        // Persist OrderItems EXPLICITLY (no cascade exists; FKs are plain Long).
        List<OrderItem> items = new ArrayList<>();
        for (LineItemPreview line : computed.getItems()) {
            OrderItem oi = new OrderItem();
            oi.setOrderId(orderId);
            oi.setProductId(line.getProductId());
            oi.setVendorId(line.getVendorId());
            oi.setQuantity(line.getQuantity());
            oi.setUnitPrice(line.getUnitPrice());
            oi.setLineTotal(line.getLineTotal());
            items.add(oi);
        }
        orderItemRepository.saveAll(items);

        // Increment member total_spend and stamp tier_updated_at.
        Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new MemberNotFoundException("Member not found: " + memberId));
        BigDecimal currentSpend = member.getTotalSpend() != null ? member.getTotalSpend() : BigDecimal.ZERO;
        member.setTotalSpend(currentSpend.add(computed.getTotal()));
        member.setTierUpdatedAt(LocalDateTime.now());
        memberRepository.save(member);

        // Clear the draft cart.
        orderDraftItemRepository.deleteByMemberId(memberId);

        return orderId;
    }

    /**
     * Returns a single order by ID, or {@code null} if not found.
     *
     * @param orderId  the order ID
     * @return         the {@link Order} entity, or {@code null} when absent
     */
    public Order getOrder(Long orderId) {
        // findById now returns Optional; map empty -> null to preserve the REST null -> 404 contract.
        return orderRepository.findById(orderId).orElse(null);
    }

    /**
     * Returns the order history for a member, sorted by creation date descending.
     *
     * @param memberId  the member ID
     * @return          list of orders sorted by {@code created_at} descending
     */
    public List<Order> getOrderHistory(Long memberId) {
        return orderRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    // ---- Unified read-only computation shared by preview and submit (AAP §0.6.4) ----
    private OrderPreview orchestrateOrder(Long memberId, String destinationZip, boolean expedite) {
        // 1. Member must exist (SP: NOT FOUND => RAISE 'P0003').
        memberRepository.findById(memberId)
            .orElseThrow(() -> new MemberNotFoundException("Member not found: " + memberId));

        // 2. Draft cart must be non-empty (SP: empty => RAISE 'P0004').
        List<OrderDraftItem> draftItems = orderDraftItemRepository.findByMemberId(memberId);
        if (draftItems.isEmpty()) {
            throw new EmptyCartException("Cart is empty for member " + memberId);
        }

        // 3. Batch-load every draft product ONCE for weight lookup. This replaces the former
        // per-line productRepository.findById(...) inside the loop (part of the order-orchestration
        // N+1 fix). COALESCE(weight_lbs, 0) is applied as each product is mapped; absent products
        // simply do not appear in the map (a line whose product has no stocked vendor fails below via
        // InventoryNotFoundException before its weight would ever be needed).
        List<Long> productIds = new ArrayList<>();
        for (OrderDraftItem draftItem : draftItems) {
            productIds.add(draftItem.getProductId());
        }
        Map<Long, BigDecimal> weightByProduct = new HashMap<>();
        for (Product product : productRepository.findAllById(productIds)) {
            BigDecimal weight = product.getWeightLbs();
            weightByProduct.put(product.getId(), weight != null ? weight : BigDecimal.ZERO);
        }

        // 4. Per draft line: best vendor AND its unit price come back from ONE projection query (no
        // per-candidate calculate_price reloads and no per-candidate vendor lookups); line total and
        // weight are computed from already-loaded data. Accumulate subtotal + total weight.
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        List<LineItemPreview> lineItems = new ArrayList<>();
        for (OrderDraftItem draftItem : draftItems) {
            Long productId = draftItem.getProductId();
            int quantity = draftItem.getQuantity();

            VendorSelectionService.VendorSelection selection =
                vendorSelectionService.selectBestVendorWithPrice(productId, quantity);
            if (selection == null) {
                // BEHAVIOR PRESERVATION (AAP §0.7.1 "preserve observable behavior"): process_order does NOT
                // skip an unfulfillable line. The SP assigns v_vendor_id := select_best_vendor(...) and then
                // immediately calls calculate_price(product_id, NULL, qty); with a NULL vendor no
                // vendor_inventory row matches, so calculate_price RAISEs ERRCODE 'P0001' and the whole order
                // aborts. Mirror that here: a draft line with no selectable vendor (no inventory with stock)
                // fails the entire preview/submit with InventoryNotFoundException (mapped to 404 by
                // RestExceptionHandler) instead of silently dropping the line — which could otherwise yield a
                // partial or effectively empty order.
                throw new InventoryNotFoundException(
                    "No vendor with available inventory for product " + productId
                        + " (requested quantity " + quantity + ")");
            }
            Long vendorId = selection.getVendorId();
            // Unit price was computed during selection from the same base price + markup that
            // calculatePrice(productId, vendorId, quantity) would load — identical value, no reload.
            BigDecimal unitPrice = selection.getUnitPrice();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(lineTotal);

            // COALESCE(weight_lbs, 0) * qty from the preloaded weight map (Product weight is nullable).
            BigDecimal unitWeight = weightByProduct.getOrDefault(productId, BigDecimal.ZERO);
            totalWeight = totalWeight.add(unitWeight.multiply(BigDecimal.valueOf(quantity)));

            lineItems.add(new LineItemPreview(productId, vendorId, quantity, unitPrice, lineTotal));
        }

        // 5. Discount (writes EXACTLY ONE discount_audit row via DiscountService).
        BigDecimal discountAmount = discountService.calculateDiscount(memberId, subtotal);
        // 6. Shipping on accumulated weight.
        BigDecimal shippingCost = shippingService.calculateShipping(destinationZip, totalWeight, expedite);
        // 7. total = subtotal - discount + shipping.
        BigDecimal total = subtotal.subtract(discountAmount).add(shippingCost).setScale(2, RoundingMode.HALF_UP);

        return new OrderPreview(subtotal, discountAmount, shippingCost, total, lineItems);
    }

    // ---- Inner classes (PRESERVE EXACTLY — REST serializes these to JSON) ----
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
