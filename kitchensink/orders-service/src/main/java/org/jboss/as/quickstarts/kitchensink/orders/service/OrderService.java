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
import org.jboss.as.quickstarts.kitchensink.orders.exception.EmptyCartException;
import org.jboss.as.quickstarts.kitchensink.orders.model.Order;
import org.jboss.as.quickstarts.kitchensink.orders.model.OrderDraftItem;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderDraftItemRepository;
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

    // Constructor injection ONLY (single constructor -> no @Autowired required; no field injection).
    private final OrderRepository orderRepository;
    private final OrderDraftItemRepository orderDraftItemRepository;
    private final MarketplaceClient marketplaceClient;
    private final UsersClient usersClient;
    private final DiscountService discountService;
    private final ShippingService shippingService;
    // Dedicated transactional collaborator that owns the order/items/cart-clear persistence boundary.
    // Replaces the former @Autowired @Lazy self-proxy: submitOrder() stays NON-transactional (so the
    // cross-service HTTP in orchestrateOrder() runs outside any transaction, §0.7.2), and the
    // @Transactional persist step is reached through this ordinary collaborator bean rather than a
    // self-injected proxy -- honoring the constructor-injection-only rule.
    private final OrderPersistenceService orderPersistenceService;

    public OrderService(OrderRepository orderRepository,
                        OrderDraftItemRepository orderDraftItemRepository,
                        MarketplaceClient marketplaceClient,
                        UsersClient usersClient,
                        DiscountService discountService,
                        ShippingService shippingService,
                        OrderPersistenceService orderPersistenceService) {
        this.orderRepository = orderRepository;
        this.orderDraftItemRepository = orderDraftItemRepository;
        this.marketplaceClient = marketplaceClient;
        this.usersClient = usersClient;
        this.discountService = discountService;
        this.shippingService = shippingService;
        this.orderPersistenceService = orderPersistenceService;
    }

    // ----- cart operations -----

    /**
     * Adds a product to the member's draft cart.
     *
     * <p><b>Product-existence validation (QA fix).</b> The {@code order_draft_items.product_id}
     * column is a foreign key onto {@code products(id)} (db/01_schema.sql). Persisting a draft row
     * with an unknown {@code productId} therefore trips that FK and surfaces as a generic HTTP 500
     * ({@code DataIntegrityViolationException} / {@code PSQLException}) — an internal database error
     * driven by ordinary invalid client input. To close that gap we first validate the product with
     * marketplace-service (the catalog's system of record) via {@link MarketplaceClient#verifyProductExists};
     * an unknown product surfaces as {@link org.jboss.as.quickstarts.kitchensink.orders.exception.InventoryNotFoundException}
     * (HTTP 404) BEFORE any row is written. orders-service cannot import marketplace types, so this
     * cross-service check is HTTP-only through the thin client.</p>
     *
     * <p><b>Intentionally NON-transactional.</b> The cross-service HTTP probe must run OUTSIDE any
     * transaction (transaction-vs-HTTP ordering, §0.7.2) so a DB connection is never held across a
     * network round-trip. The subsequent single {@code save} is itself atomic (Spring Data's
     * {@code SimpleJpaRepository.save} is {@code @Transactional}), so no method-level boundary is
     * required here.</p>
     */
    public void addToCart(Long memberId, Long productId, int quantity) {
        // Guard: reject an unknown productId as a clean 404 before touching the database, so the
        // products(id) foreign key is never violated (which would otherwise leak a 500).
        marketplaceClient.verifyProductExists(productId);

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
     * Reproduces the calculation core of process_order (db/02_stored_procedures.sql L196-290),
     * INCLUDING its two leading guards (SQL L216-232):
     * <ol>
     *   <li><b>member-exists</b> (SQL L217-221, {@code RAISE ... ERRCODE 'P0003'}): the member is
     *       validated through the users-service Contract-2 tier endpoint. A missing member surfaces
     *       as {@link org.jboss.as.quickstarts.kitchensink.orders.exception.MemberNotFoundException}
     *       (HTTP 404). The resolved tier is reused for the discount step so no second HTTP call is
     *       made; and</li>
     *   <li><b>cart-not-empty</b> (SQL L224-232, {@code RAISE ... ERRCODE 'P0004'}): an empty draft
     *       cart is rejected with {@link EmptyCartException} (HTTP 400) BEFORE any line, discount,
     *       shipping, or persistence work -- preventing a confirmed zero-subtotal order that would
     *       still apply the shipping floor and trigger a spend increment.</li>
     * </ol>
     * Then, for each draft item resolve a marketplace quote {vendorId, unitPrice, weightLbs},
     * lineTotal = ROUND(unitPrice * qty, 2), accumulate subtotal and total weight; apply the
     * customer discount and shipping; total = subtotal - discount + shipping.
     *
     * <p>ALL cross-service HTTP (the member/tier lookup + marketplace quotes) occurs HERE, before any
     * transaction is opened (transaction-vs-HTTP ordering, §0.7.2). The guard order mirrors the
     * stored procedure: member existence is checked before the empty-cart check.</p>
     */
    private OrderPreview orchestrateOrder(Long memberId, String destinationZip, boolean expedite) {
        // Guard 1 -- member exists (SQL L217-221). The users-service tier endpoint (Contract 2) is the
        // member's system of record; a 404 there maps (in UsersClient) to MemberNotFoundException ->
        // HTTP 404. The returned tier is reused below for the discount, so this existence probe costs
        // no extra round-trip.
        String tier = usersClient.getMemberTier(memberId);

        // Guard 2 -- cart not empty (SQL L224-232). Reject an empty cart before any calculation or
        // persistence so a confirmed empty order can never be created.
        List<OrderDraftItem> draftItems = orderDraftItemRepository.findByMemberId(memberId);
        if (draftItems.isEmpty()) {
            throw new EmptyCartException("Cart is empty for member " + memberId);
        }

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
        // Reuse the tier already resolved by Guard 1 (3-arg overload) so the discount step does NOT
        // issue a second tier HTTP call for the same order.
        BigDecimal discountAmount = discountService.calculateDiscount(memberId, subtotal, tier);
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
     * @Transactional boundary (delegated to {@link OrderPersistenceService}), and finally -- AFTER
     * commit -- triggers the idempotent member-spend increment.
     */
    public Long submitOrder(Long memberId, String destinationZip, boolean expedite) {
        // Phase 1: calculation/preview. All marketplace + users HTTP happens here, BEFORE the tx
        // (this also runs the member-exists and empty-cart guards; see orchestrateOrder()).
        OrderPreview preview = orchestrateOrder(memberId, destinationZip, expedite);

        // Phase 2: single local transactional persist (order + items + cart clear). Delegated to the
        // constructor-injected OrderPersistenceService whose persistConfirmedOrder() carries the
        // @Transactional boundary; this ordinary cross-bean call (no self-proxy) ensures the boundary
        // is applied so the order, its items, and the cart-clear commit atomically.
        Long orderId = orderPersistenceService.persistConfirmedOrder(memberId, preview);

        // Phase 3: GAP-3 (§0.6.6) member total_spend increment. Intentionally POST-COMMIT and outside
        // the orders transaction: orders-service must not write the member table, and the call crosses
        // a service boundary over HTTP. It is therefore eventually consistent -- the order can commit
        // even if this increment fails.
        //
        // IDEMPOTENCY (§0.6.6 recommended resolution): the just-persisted orderId is passed as an
        // idempotency key. orders.id is the orders table's globally-unique primary key, so users-service
        // applies each order's spend AT MOST ONCE even if this post-commit call is retried or duplicated
        // (the dedupe lives in users-service's MemberSpendService). This is the durable correctness
        // guarantee for total_spend; it does NOT rely on the nightly tier recalculation reconciling
        // spend (that job recomputes only the tier, from the trailing-90-day window, and never rewrites
        // the lifetime total_spend column). The call MUST occur because OrderServiceIT asserts the
        // post-commit increment fires exactly once, carrying the orderId key and the order total.
        usersClient.incrementMemberSpend(memberId, orderId, preview.getTotal());

        return orderId;
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
     *
     * <p>The cutoff comparison is INCLUSIVE ({@code created_at >= now - days}) to match the stored
     * procedure / AAP semantics ({@code created_at >= NOW() - INTERVAL '90 days'},
     * db/02_stored_procedures.sql); an order placed exactly at the window boundary still counts.</p>
     */
    public BigDecimal computeMemberSpend(Long memberId, int days) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<Order> orders =
                orderRepository.findByMemberIdAndStatusAndCreatedAtGreaterThanEqual(memberId, "CONFIRMED", cutoff);
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
