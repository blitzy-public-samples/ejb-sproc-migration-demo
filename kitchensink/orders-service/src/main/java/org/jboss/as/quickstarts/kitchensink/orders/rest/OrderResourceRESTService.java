package org.jboss.as.quickstarts.kitchensink.orders.rest;

import java.util.List;
import java.util.Map;

import org.jboss.as.quickstarts.kitchensink.orders.exception.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.MemberNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException;
import org.jboss.as.quickstarts.kitchensink.orders.model.Order;
import org.jboss.as.quickstarts.kitchensink.orders.service.OrderService;
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

/**
 * Spring MVC REST controller for the orders bounded context.
 *
 * <p>This is the faithful Spring MVC conversion of the monolith JAX-RS resource
 * {@code org.jboss.as.quickstarts.kitchensink.rest.OrderResourceRESTService}. It is
 * relocated into the orders-service microservice and re-annotated from JAX-RS to Spring
 * MVC; every externally consumed endpoint preserves its HTTP verb, path, query/path
 * parameter names, status codes, message strings, and JSON response shapes exactly
 * (CONTRACT PRESERVATION, AAP &sect;0.7.1). The single sanctioned structural change is
 * relocating the class mapping under {@code /api/orders}.</p>
 *
 * <p>Served under the orders-service context-path {@code /orders} (port 8082), so the
 * externally visible full paths are {@code /orders/api/orders/...}.</p>
 *
 * <p>Cross-domain boundary rule (AAP &sect;0.7.2): this controller depends on NOTHING from
 * the {@code ...marketplace...} or {@code ...users...} packages. It collaborates solely with
 * this service's {@link OrderService} bean, which performs any cross-domain reads over HTTP
 * through its client gateways.</p>
 *
 * <p>Exception mapping follows the AAP &sect;0.3.3 "centralized exception mapping" pattern and the
 * Contract Authority (AAP &sect;0.6.2 / &sect;0.7.3). The three cross-domain domain exceptions are
 * mapped to their contractual HTTP status by {@link OrdersRestExceptionHandler}
 * ({@code @RestControllerAdvice}): {@link MemberNotFoundException} and
 * {@link InventoryNotFoundException} &rarr; {@code 404}, {@link ServiceUnavailableException} &rarr;
 * {@code 503}. Because {@code previewOrder}/{@code submitOrder} wrap their service call in a broad
 * {@code try/catch} (whose generic {@code catch (Exception e)} surfaces all <em>other</em> compute
 * failures — empty cart, request-body type mismatch, foreign-key violation, overflow — as a faithful
 * {@code 500 INTERNAL_SERVER_ERROR}, preserving monolith behavior per AAP &sect;0.7.2), those two
 * methods explicitly <em>re-throw</em> the three domain exceptions ahead of the generic catch so they
 * propagate to the advice instead of being flattened to {@code 500}. This resolves QA INC-2 Finding F1
 * (Contract 2 missing-member must surface as {@code 404}, not {@code 500}). The {@code @Transactional}
 * boundary inside {@link OrderService} still performs rollback before the exception propagates here.</p>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderResourceRESTService {

    private final OrderService orderService;

    /**
     * Constructor injection (replaces the monolith's CDI {@code @Inject} field). Spring supplies
     * the singleton {@link OrderService} bean.
     *
     * @param orderService the orders domain service
     */
    public OrderResourceRESTService(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * POST /api/orders/cart/{memberId}
     * Adds a product to the member's draft cart.
     * Body: { "productId": N, "quantity": N }
     *
     * <p>Returns {@code 400 BAD_REQUEST} when the body is missing either {@code productId} or
     * {@code quantity}, or when {@code quantity <= 0}; otherwise persists the draft item and
     * returns {@code 201 CREATED}.</p>
     *
     * @param memberId the owning member's id (path variable)
     * @param body     the request payload carrying {@code productId} and {@code quantity}
     * @return a {@link ResponseEntity} mirroring the monolith's status codes and message strings
     */
    @PostMapping("/cart/{memberId}")
    public ResponseEntity<?> addToCart(
            @PathVariable("memberId") Long memberId,
            @RequestBody(required = false) Map<String, Object> body) {
        if (body == null || !body.containsKey("productId") || !body.containsKey("quantity")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Request body must contain productId and quantity");
        }
        Long productId = ((Number) body.get("productId")).longValue();
        int quantity = ((Number) body.get("quantity")).intValue();
        if (quantity <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("quantity must be greater than zero");
        }
        orderService.addToCart(memberId, productId, quantity);
        return ResponseEntity.status(HttpStatus.CREATED).body("Item added to cart");
    }

    /**
     * DELETE /api/orders/cart/{memberId}/{productId}
     * Removes a product from the member's draft cart.
     *
     * @param memberId  the owning member's id (path variable)
     * @param productId the product id to remove from the draft cart (path variable)
     * @return {@code 200 OK} with the confirmation message
     */
    @DeleteMapping("/cart/{memberId}/{productId}")
    public ResponseEntity<?> removeFromCart(
            @PathVariable("memberId") Long memberId,
            @PathVariable("productId") Long productId) {
        orderService.removeFromCart(memberId, productId);
        return ResponseEntity.ok("Item removed from cart");
    }

    /**
     * GET /api/orders/cart/{memberId}/preview?zip=XXXXX&amp;expedite=true|false
     * Returns a full order preview with pricing, discount, and shipping breakdown.
     *
     * <p>The {@code zip} query parameter is intentionally REQUIRED (ambiguity A5): it is declared
     * {@code required=false} at the binding layer so that an absent value yields the monolith's
     * exact {@code 400} message rather than Spring's default missing-parameter error.</p>
     *
     * @param memberId the owning member's id (path variable)
     * @param zip      destination ZIP used for shipping computation (required)
     * @param expedite whether to apply expedited shipping (defaults to {@code false})
     * @return {@code 400} when {@code zip} is blank, {@code 200} with the {@code OrderPreview}
     *         JSON on success, or {@code 500} with a message when the compute fails
     */
    @GetMapping("/cart/{memberId}/preview")
    public ResponseEntity<?> previewOrder(
            @PathVariable("memberId") Long memberId,
            @RequestParam(name = "zip", required = false) String zip,
            @RequestParam(name = "expedite", defaultValue = "false") boolean expedite) {
        if (zip == null || zip.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("zip query parameter is required");
        }
        try {
            OrderService.OrderPreview preview = orderService.previewOrder(memberId, zip, expedite);
            return ResponseEntity.ok(preview);
        } catch (MemberNotFoundException | InventoryNotFoundException | ServiceUnavailableException e) {
            // Cross-domain domain exceptions carry their own contractual HTTP status (404/404/503,
            // AAP §0.6.2). Re-throw so OrdersRestExceptionHandler (@RestControllerAdvice) maps them,
            // rather than letting the generic catch below flatten them to 500 (QA INC-2 Finding F1).
            throw e;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Could not preview order: " + e.getMessage());
        }
    }

    /**
     * POST /api/orders/submit/{memberId}?zip=XXXXX&amp;expedite=true|false
     * Submits the order. Returns the new order ID.
     *
     * @param memberId the owning member's id (path variable)
     * @param zip      destination ZIP used for shipping computation (required)
     * @param expedite whether to apply expedited shipping (defaults to {@code false})
     * @return {@code 400} when {@code zip} is blank, {@code 201} with {@code {"orderId": N}}
     *         on success, or {@code 500} with a message when the submit fails
     */
    @PostMapping("/submit/{memberId}")
    public ResponseEntity<?> submitOrder(
            @PathVariable("memberId") Long memberId,
            @RequestParam(name = "zip", required = false) String zip,
            @RequestParam(name = "expedite", defaultValue = "false") boolean expedite) {
        if (zip == null || zip.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("zip query parameter is required");
        }
        try {
            Long orderId = orderService.submitOrder(memberId, zip, expedite);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("orderId", orderId));
        } catch (MemberNotFoundException | InventoryNotFoundException | ServiceUnavailableException e) {
            // Cross-domain domain exceptions carry their own contractual HTTP status (404/404/503,
            // AAP §0.6.2). Re-throw so OrdersRestExceptionHandler (@RestControllerAdvice) maps them,
            // rather than letting the generic catch below flatten them to 500 (QA INC-2 Finding F1).
            throw e;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Could not submit order: " + e.getMessage());
        }
    }

    /**
     * GET /api/orders/{orderId}
     * Returns a single order by ID, or 404 if not found.
     *
     * @param orderId the order id (path variable)
     * @return {@code 200} with the {@code Order} JSON when found, or {@code 404} with a message
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<?> getOrder(@PathVariable("orderId") Long orderId) {
        Order order = orderService.getOrder(orderId);
        if (order == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body("Order not found: " + orderId);
        }
        return ResponseEntity.ok(order);
    }

    /**
     * GET /api/orders/member/{memberId}
     * Returns the order history for a member.
     *
     * @param memberId the member id (path variable)
     * @return {@code 200} with the JSON array of the member's orders (possibly empty)
     */
    @GetMapping("/member/{memberId}")
    public ResponseEntity<?> getOrderHistory(@PathVariable("memberId") Long memberId) {
        List<Order> orders = orderService.getOrderHistory(memberId);
        return ResponseEntity.ok(orders);
    }
}
