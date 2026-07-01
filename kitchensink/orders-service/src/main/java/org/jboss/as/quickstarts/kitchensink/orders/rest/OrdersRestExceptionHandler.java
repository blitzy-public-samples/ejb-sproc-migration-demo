package org.jboss.as.quickstarts.kitchensink.orders.rest;

import org.jboss.as.quickstarts.kitchensink.orders.exception.EmptyCartException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.MemberNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.NoEligibleVendorException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.ProductNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralized exception mapping for the orders-service REST layer.
 *
 * <p>This advice realizes the AAP &sect;0.3.3 design pattern <em>"Centralized exception mapping —
 * domain exceptions map to HTTP status codes via {@code @RestControllerAdvice}"</em> and enforces
 * the cross-domain Contract Authority (AAP &sect;0.6.2 / &sect;0.7.3), which is the single source of
 * truth for inter-service error surfacing:</p>
 * <ul>
 *   <li><strong>Contract 1 (Pricing), orders &rarr; marketplace:</strong> a 404 from marketplace
 *       (no vendor_inventory row) is raised by {@code MarketplaceClientImpl} as
 *       {@link InventoryNotFoundException} and MUST surface to the caller as <strong>HTTP 404</strong>;
 *       a 5xx / transport failure becomes {@link ServiceUnavailableException} &rarr; <strong>503</strong>.</li>
 *   <li><strong>Contract 2 (Tier), orders &rarr; users:</strong> a 404 from users (member does not
 *       exist) is raised by {@code UsersClientImpl} as {@link MemberNotFoundException} and MUST
 *       surface as <strong>HTTP 404</strong>, NOT 500; a 5xx / transport failure becomes
 *       {@link ServiceUnavailableException} &rarr; <strong>503</strong>.</li>
 * </ul>
 *
 * <p><strong>Why this advice is required (QA INC-2, Finding F1, MAJOR).</strong> The three domain
 * exceptions are already annotated with {@code @ResponseStatus} (404/404/503), but
 * {@code @ResponseStatus} only takes effect when the exception <em>propagates out</em> of the MVC
 * handler method. The {@code previewOrder}/{@code submitOrder} handlers in
 * {@link OrderResourceRESTService} wrap their service call in a broad
 * {@code try { ... } catch (Exception e) { return 500; }}; that catch-all intercepted the domain
 * exceptions first and flattened them to {@code 500}, so the Contract 2 missing-member case returned
 * {@code 500} instead of the contractually required {@code 404}. The fix has two coordinated parts:
 * (1) this advice maps each domain exception to its correct status, and (2) those two handlers now
 * re-throw the domain exceptions <em>before</em> their generic {@code catch (Exception e)} so the
 * exceptions reach this advice. The generic {@code 500} fallback is preserved verbatim for every other
 * unexpected failure (request-body type-mismatch casts, integer overflow) — those remain faithful
 * {@code 500} responses per the AAP minimal-change clause (AAP &sect;0.7.2; QA INFO-1).</p>
 *
 * <p><strong>Add-to-cart FK validation (QA Issue 2, MAJOR).</strong> Adding a draft cart row for an
 * unknown member or product previously reached the database and failed with a
 * {@code DataIntegrityViolationException} (order_draft_items FK violation), surfacing as an
 * uncontrolled {@code 500} with FK stack traces in the logs. {@code OrderService.addToCart} now
 * validates both foreign-key targets over HTTP before persisting and raises {@link
 * MemberNotFoundException} / {@link ProductNotFoundException}; both map to <strong>404</strong> here.</p>
 *
 * <p><strong>Empty-cart consistency (QA Issue 3, MAJOR).</strong> An empty cart is no longer a
 * {@code 500}: {@code previewOrder} and {@code submitOrder} both raise {@link EmptyCartException},
 * mapped to a single controlled <strong>400</strong> here, so the two paths agree on empty-cart
 * validity and the storefront can render a non-payable empty-cart state.</p>
 *
 * <p>This advice takes precedence over the {@code @ResponseStatus} self-mapping on each exception
 * (as each exception's javadoc anticipates), giving a single, central place that governs the whole
 * class of cross-domain error mappings for both the consumer side now and for Contract 1 once
 * marketplace-service is live.</p>
 *
 * <p><strong>Response body.</strong> Each handler returns the exception's own detail message as a
 * plain-text body, matching the established error-body style of {@link OrderResourceRESTService}
 * (e.g. {@code "Order not found: 42"}, {@code "zip query parameter is required"}). These messages are
 * leakage-safe (CWE-209): they carry only human-readable domain text and the user-supplied
 * identifier — never a stack trace, SQL, or schema detail.</p>
 *
 * <p><strong>Boundary rule (AAP &sect;0.7.2).</strong> This class imports only this service's own
 * {@code exception/} types and Spring MVC; it references nothing from the {@code ...marketplace...}
 * or {@code ...users...} packages.</p>
 */
@RestControllerAdvice
public class OrdersRestExceptionHandler {

    /**
     * Maps {@link MemberNotFoundException} (Contract 2 consumer: users-service returned 404 for a
     * member-tier lookup) to {@code HTTP 404 NOT_FOUND}.
     *
     * @param ex the raised domain exception
     * @return a {@code 404} response whose plain-text body is the exception's detail message
     */
    @ExceptionHandler(MemberNotFoundException.class)
    public ResponseEntity<String> handleMemberNotFound(MemberNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    /**
     * Maps {@link InventoryNotFoundException} (Contract 1 consumer: marketplace-service returned 404
     * for a price lookup, i.e. no vendor_inventory row) to {@code HTTP 404 NOT_FOUND}.
     *
     * @param ex the raised domain exception
     * @return a {@code 404} response whose plain-text body is the exception's detail message
     */
    @ExceptionHandler(InventoryNotFoundException.class)
    public ResponseEntity<String> handleInventoryNotFound(InventoryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    /**
     * Maps {@link ProductNotFoundException} (QA Issue 2: {@code addToCart} referenced a product that
     * does not exist in the marketplace catalog) to {@code HTTP 404 NOT_FOUND}. This converts what was
     * a raw {@code DataIntegrityViolationException} (order_draft_items product FK violation, HTTP 500)
     * into a controlled, leakage-safe 404 raised <em>before</em> any row is persisted.
     *
     * @param ex the raised domain exception
     * @return a {@code 404} response whose plain-text body is the exception's detail message
     */
    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<String> handleProductNotFound(ProductNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    /**
     * Maps {@link EmptyCartException} (QA Issue 3: an order preview or submit was attempted with an
     * empty cart) to {@code HTTP 400 BAD_REQUEST}. Both the preview and submit paths raise this same
     * exception, so they agree on empty-cart validity instead of the previous split behavior (preview
     * returned a misleading payable-looking 200 while submit returned an uncontrolled 500). 400 is
     * consistent with the controller's other client-precondition failures (missing {@code zip},
     * non-positive quantity).
     *
     * @param ex the raised domain exception
     * @return a {@code 400} response whose plain-text body is the exception's detail message
     */
    @ExceptionHandler(EmptyCartException.class)
    public ResponseEntity<String> handleEmptyCart(EmptyCartException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }

    /**
     * Maps {@link NoEligibleVendorException} (a cart line has no eligible/in-stock vendor at order
     * orchestration time) to {@code HTTP 409 CONFLICT} (review M1). The request is well-formed but
     * cannot be fulfilled in the current catalog/inventory state, so it is a conflict with server
     * state rather than a 400 (bad input) or 404 (missing resource). This handler ensures the order
     * aborts with a clear status instead of being flattened to a generic {@code 500} by the broad
     * {@code catch (Exception e)} in {@link OrderResourceRESTService} (those handlers re-throw this
     * exception ahead of the generic catch so it reaches this advice).
     *
     * @param ex the raised domain exception
     * @return a {@code 409} response whose plain-text body is the exception's detail message
     */
    @ExceptionHandler(NoEligibleVendorException.class)
    public ResponseEntity<String> handleNoEligibleVendor(NoEligibleVendorException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    /**
     * Maps {@link ServiceUnavailableException} (a downstream marketplace-/users-service returned 5xx
     * or was unreachable) to {@code HTTP 503 SERVICE_UNAVAILABLE}, the contractually correct status
     * for a transient downstream failure (AAP &sect;0.6.2).
     *
     * @param ex the raised domain exception
     * @return a {@code 503} response whose plain-text body is the exception's detail message
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<String> handleServiceUnavailable(ServiceUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ex.getMessage());
    }
}
