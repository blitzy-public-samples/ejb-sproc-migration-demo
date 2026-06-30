package org.jboss.as.quickstarts.kitchensink.orders.rest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
}
