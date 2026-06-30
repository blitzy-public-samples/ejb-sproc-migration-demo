package org.jboss.as.quickstarts.kitchensink.orders.rest;

import org.jboss.as.quickstarts.kitchensink.orders.exception.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.MemberNotFoundException;
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
 * re-throw the three domain exceptions <em>before</em> their generic {@code catch (Exception e)} so the
 * exceptions reach this advice. The generic {@code 500} fallback is preserved verbatim for every other
 * failure (empty cart, request-body type-mismatch casts, foreign-key violations, integer overflow) —
 * those remain faithful {@code 500} responses per the AAP minimal-change clause (AAP &sect;0.7.2; QA
 * INFO-1).</p>
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
