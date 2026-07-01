package org.jboss.as.quickstarts.kitchensink.orders.rest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import org.jboss.as.quickstarts.kitchensink.orders.dto.AddToCartRequest;
import org.jboss.as.quickstarts.kitchensink.orders.model.Order;
import org.jboss.as.quickstarts.kitchensink.orders.service.OrderService;

/**
 * REST edge for the orders-service shopping cart and order surface. Spring MVC replacement for the
 * legacy JAX-RS OrderResourceRESTService. All legacy paths and HTTP status semantics are preserved
 * under the base mapping /api/orders (combined with context-path /orders).
 */
@RestController
@RequestMapping("/api/orders")
public class OrderResourceRESTService {

    private final OrderService orderService;

    // Constructor injection (single constructor -> no @Autowired required).
    public OrderResourceRESTService(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * POST /api/orders/cart/{memberId} - add a product to the member's draft cart.
     * Body: { "productId": N, "quantity": N }. 201 on success; 400 on a missing/malformed body or an
     * invalid field.
     *
     * <p>The body is bound to a typed, {@code @Valid}-checked {@link AddToCartRequest} DTO. This
     * removes the previous raw-{@code Map} blind casts that turned malformed JSON (e.g. string
     * values) into a {@code ClassCastException} -> HTTP 500. Now: a missing/unparseable body is a
     * {@code HttpMessageNotReadableException} (400) and a null/non-positive field is a
     * {@code MethodArgumentNotValidException} (400) -- both handled below.</p>
     */
    @PostMapping("/cart/{memberId}")
    public ResponseEntity<Object> addToCart(
            @PathVariable Long memberId,
            @Valid @RequestBody AddToCartRequest request) {
        orderService.addToCart(memberId, request.getProductId(), request.getQuantity());
        return ResponseEntity.status(HttpStatus.CREATED).body("Item added to cart");
    }

    /** DELETE /api/orders/cart/{memberId}/{productId} - remove a product from the draft cart. */
    @DeleteMapping("/cart/{memberId}/{productId}")
    public ResponseEntity<Object> removeFromCart(
            @PathVariable Long memberId,
            @PathVariable Long productId) {
        orderService.removeFromCart(memberId, productId);
        return ResponseEntity.ok("Item removed from cart");
    }

    /** GET /api/orders/cart/{memberId}/preview?zip=&expedite= - full order preview; 400 if zip blank. */
    @GetMapping("/cart/{memberId}/preview")
    public ResponseEntity<Object> previewOrder(
            @PathVariable Long memberId,
            @RequestParam(name = "zip", required = false) String zip,
            @RequestParam(name = "expedite", defaultValue = "false") boolean expedite) {
        if (zip == null || zip.isBlank()) {
            return ResponseEntity.badRequest().body("zip query parameter is required");
        }
        OrderService.OrderPreview preview = orderService.previewOrder(memberId, zip, expedite);
        return ResponseEntity.ok(preview);
    }

    /** POST /api/orders/submit/{memberId}?zip=&expedite= - submit the order; 201 with {orderId}; 400 if zip blank. */
    @PostMapping("/submit/{memberId}")
    public ResponseEntity<Object> submitOrder(
            @PathVariable Long memberId,
            @RequestParam(name = "zip", required = false) String zip,
            @RequestParam(name = "expedite", defaultValue = "false") boolean expedite) {
        if (zip == null || zip.isBlank()) {
            return ResponseEntity.badRequest().body("zip query parameter is required");
        }
        Long orderId = orderService.submitOrder(memberId, zip, expedite);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("orderId", orderId));
    }

    /**
     * GET /api/orders/{orderId} - one order, or 404 if not found (legacy semantics preserved).
     *
     * <p>This is part of the public order surface and is intentionally unauthenticated to preserve the
     * frozen PHP storefront's continuity (AAP §0.3.4, §0.7.1): the legacy JAX-RS resource carried no
     * authentication and returned the order (or 404) to any caller. See {@code InternalSecurityConfig}.</p>
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<Object> getOrder(@PathVariable Long orderId) {
        Order order = orderService.getOrder(orderId);
        if (order == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Order not found: " + orderId);
        }
        return ResponseEntity.ok(order);
    }

    /** GET /api/orders/member/{memberId} - order history for a member (always 200). */
    @GetMapping("/member/{memberId}")
    public List<Order> getOrderHistory(@PathVariable Long memberId) {
        return orderService.getOrderHistory(memberId);
    }

    // ----- input-validation error handling (clean 400s, never a 500) -----

    /**
     * Bean Validation failure on a request body (e.g. {@link AddToCartRequest} with a null or
     * non-positive field) -> 400 with a concise field-error summary.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(message.isBlank() ? "Request validation failed" : message);
    }

    /**
     * Missing or malformed request body (e.g. non-numeric productId/quantity, or absent body) ->
     * 400 instead of the former {@code ClassCastException}-driven 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body("Request body is missing or malformed; productId and quantity must be numbers");
    }

    // ----- concurrency error handling (graceful 409 on a double-submit race, never a 500) -----

    /**
     * Concurrency-safe order-submission mapping -> 409 Conflict with body
     * {@code {"error":"Cart already submitted; please retry."}}.
     *
     * <p><b>The race.</b> {@link #submitOrder} runs {@code OrderService.orchestrateOrder()} (all
     * cross-service HTTP, OUTSIDE any transaction) and then persists the CONFIRMED order + items and
     * clears the member's draft cart inside a single local {@code @Transactional} boundary
     * ({@code OrderPersistenceService.persistConfirmedOrder}). That check-then-persist is correct for
     * the common sequential case, but under a near-simultaneous double-submit of the SAME cart
     * (double-click / rapid retry) both requests can finish their pre-transaction calculation before
     * either commits. The winner persists its order and deletes the member's {@code order_draft_items}
     * rows; the loser then attempts to delete those same (now-gone) rows and Hibernate's row-count
     * check surfaces the stale state as {@link ObjectOptimisticLockingFailureException} (wrapping
     * {@code org.hibernate.StaleObjectStateException}). Without a mapping this benign, retryable
     * outcome would leak a generic HTTP 500.</p>
     *
     * <p><b>Integrity is preserved, not relaxed.</b> The stale-state detection is exactly what keeps
     * the submission atomic: precisely ONE order is ever persisted per race, with no orphan
     * {@code order_items} and no double-charge. This handler does NOT weaken the single-{@code
     * @Transactional} atomicity guarantee (AAP &sect;0.7.1) -- it only translates the loser's outcome
     * into a graceful 409. A client that receives this 409 can simply retry; the retry finds an empty
     * cart and returns the graceful 400 (empty cart).</p>
     *
     * <p><b>Targeted, not catch-all.</b> This maps a single EXPECTED concurrency exception, mirroring
     * the graceful-conflict pattern already used on the users-service registration edge
     * ({@code @ExceptionHandler(DataIntegrityViolationException.class) -> 409} for the concurrent
     * duplicate-email race). Any other unexpected error still yields Spring's default 500. The 409
     * body uses the same {@code Map} JSON shape as the {@code submitOrder} success body
     * ({@code {"orderId":N}}), so the surface stays consistent.</p>
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleConcurrentSubmit(ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Cart already submitted; please retry."));
    }
}
