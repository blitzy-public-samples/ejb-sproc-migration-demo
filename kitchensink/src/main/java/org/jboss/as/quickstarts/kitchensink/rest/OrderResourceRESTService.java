package org.jboss.as.quickstarts.kitchensink.rest;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.jboss.as.quickstarts.kitchensink.model.Order;
import org.jboss.as.quickstarts.kitchensink.service.OrderService;

/**
 * Order and cart REST endpoints.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): converted from a JAX-RS
 * {@code @Path("/orders") @RequestScoped} resource to a Spring MVC {@code @RestController}. The base
 * path is {@code /api/orders}; with the {@code /kitchensink} context path the externally visible base
 * is {@code /kitchensink/api/orders}, matching the PHP storefront exactly. The actual legacy paths are
 * preserved verbatim: {@code POST /cart/{memberId}}, {@code DELETE /cart/{memberId}/{productId}},
 * {@code GET /cart/{memberId}/preview}, {@code POST /submit/{memberId}}, {@code GET /{orderId}}, and
 * {@code GET /member/{memberId}}.</p>
 *
 * <p>Success status codes are preserved exactly because the PHP client keys on them: add-to-cart and
 * submit return {@code 201}; submit's body is {@code {"orderId": N}}. Business-rule failures
 * (member-not-found, empty cart) are thrown by the service as unchecked exceptions and mapped to HTTP
 * status by {@link RestExceptionHandler}, replacing the legacy inline {@code WebApplicationException}
 * handling. Request-level validation (missing {@code zip}) is handled inline with a 400.</p>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderResourceRESTService {

    private final OrderService orderService;

    public OrderResourceRESTService(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * POST /api/orders/cart/{memberId} — adds a product to the member's draft cart.
     * Body: {@code { "productId": N, "quantity": N }}. Returns 201 on success.
     */
    @PostMapping("/cart/{memberId}")
    public ResponseEntity<?> addToCart(
            @PathVariable("memberId") Long memberId,
            @RequestBody Map<String, Object> body) {
        if (body == null || !body.containsKey("productId") || !body.containsKey("quantity")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Request body must contain productId and quantity");
        }
        // SECURITY / input validation (CWE-20): the body stays a Map<String,Object> for PHP-client
        // compatibility (the storefront posts {"productId":N,"quantity":N}), but the values MUST be
        // type-checked before casting. A raw (Number) cast throws ClassCastException — and a null value an
        // NPE — for malformed JSON (a string, boolean, null, or nested object), surfacing as an HTTP 500
        // instead of a clean 4xx. instanceof Number is false for null, so this also rejects null values.
        Object productIdRaw = body.get("productId");
        Object quantityRaw = body.get("quantity");
        if (!(productIdRaw instanceof Number) || !(quantityRaw instanceof Number)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("productId and quantity must be numeric");
        }
        Long productId = ((Number) productIdRaw).longValue();
        int quantity = ((Number) quantityRaw).intValue();
        if (quantity <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("quantity must be greater than zero");
        }
        // SECURITY / input validation (CWE-20, F4): reject non-positive identifiers up front with a 400.
        // memberId is a path variable (Spring already 400s a non-numeric path), but a zero/negative value
        // is still a malformed request; productId arrives in the body. Non-positive ids are nonsensical and
        // would otherwise reach the database FK layer and surface as an opaque 500. Resource EXISTENCE
        // (member/product actually present) is verified in OrderService.addToCart, which raises
        // MemberNotFoundException / InventoryNotFoundException -> 404 via RestExceptionHandler.
        if (memberId == null || memberId <= 0 || productId <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("memberId and productId must be positive");
        }
        orderService.addToCart(memberId, productId, quantity);
        return ResponseEntity.status(HttpStatus.CREATED).body("Item added to cart");
    }

    /**
     * DELETE /api/orders/cart/{memberId}/{productId} — removes a product from the member's draft cart.
     */
    @DeleteMapping("/cart/{memberId}/{productId}")
    public ResponseEntity<?> removeFromCart(
            @PathVariable("memberId") Long memberId,
            @PathVariable("productId") Long productId) {
        orderService.removeFromCart(memberId, productId);
        return ResponseEntity.ok("Item removed from cart");
    }

    /**
     * GET /api/orders/cart/{memberId}/preview?zip=XXXXX&amp;expedite=true|false — returns a full
     * order preview with pricing, discount, and shipping breakdown. A missing {@code zip} yields 400;
     * business-rule failures propagate to {@link RestExceptionHandler}.
     */
    @GetMapping("/cart/{memberId}/preview")
    public ResponseEntity<?> previewOrder(
            @PathVariable("memberId") Long memberId,
            @RequestParam(value = "zip", required = false) String zip,
            @RequestParam(value = "expedite", defaultValue = "false") boolean expedite) {
        if (zip == null || zip.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("zip query parameter is required");
        }
        OrderService.OrderPreview preview = orderService.previewOrder(memberId, zip, expedite);
        return ResponseEntity.ok(preview);
    }

    /**
     * POST /api/orders/submit/{memberId}?zip=XXXXX&amp;expedite=true|false — submits the order and
     * returns 201 with {@code {"orderId": N}}. A missing {@code zip} yields 400; member-not-found and
     * empty-cart failures propagate to {@link RestExceptionHandler}.
     */
    @PostMapping("/submit/{memberId}")
    public ResponseEntity<?> submitOrder(
            @PathVariable("memberId") Long memberId,
            @RequestParam(value = "zip", required = false) String zip,
            @RequestParam(value = "expedite", defaultValue = "false") boolean expedite) {
        if (zip == null || zip.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("zip query parameter is required");
        }
        Long orderId = orderService.submitOrder(memberId, zip, expedite);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("orderId", orderId));
    }

    /**
     * GET /api/orders/{orderId} — returns a single order by ID, or 404 if not found.
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<?> getOrder(@PathVariable("orderId") Long orderId) {
        Order order = orderService.getOrder(orderId);
        if (order == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Order not found: " + orderId);
        }
        return ResponseEntity.ok(order);
    }

    /**
     * GET /api/orders/member/{memberId} — returns the order history for a member.
     */
    @GetMapping("/member/{memberId}")
    public ResponseEntity<List<Order>> getOrderHistory(@PathVariable("memberId") Long memberId) {
        return ResponseEntity.ok(orderService.getOrderHistory(memberId));
    }
}
