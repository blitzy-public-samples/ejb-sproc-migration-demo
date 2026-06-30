package org.jboss.as.quickstarts.kitchensink.orders.rest;

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
     * Body: { "productId": N, "quantity": N }. 201 on success; 400 on missing body/keys or qty <= 0.
     * @RequestBody(required = false) keeps the explicit null-body check (and its exact 400 message)
     * authoritative, preserving the legacy JAX-RS behavior.
     */
    @PostMapping("/cart/{memberId}")
    public ResponseEntity<Object> addToCart(
            @PathVariable Long memberId,
            @RequestBody(required = false) Map<String, Object> body) {
        if (body == null || !body.containsKey("productId") || !body.containsKey("quantity")) {
            return ResponseEntity.badRequest().body("Request body must contain productId and quantity");
        }
        Long productId = ((Number) body.get("productId")).longValue();
        int quantity = ((Number) body.get("quantity")).intValue();
        if (quantity <= 0) {
            return ResponseEntity.badRequest().body("quantity must be greater than zero");
        }
        orderService.addToCart(memberId, productId, quantity);
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

    /** GET /api/orders/{orderId} - one order, or 404 if not found (legacy semantics preserved). */
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
}
