package org.jboss.as.quickstarts.kitchensink.orders.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an order preview or submit is attempted for a member whose cart
 * ({@code order_draft_items}) is empty.
 *
 * <p><strong>Why this exists (QA Issue 3 — empty-cart preview/submit consistency).</strong> Before
 * this exception, {@code submitOrder} rejected an empty cart with a raw {@code IllegalStateException}
 * that fell through to the controller's generic catch and returned an uncontrolled HTTP 500
 * ({@code "Could not submit order"}), while {@code previewOrder} performed no empty-cart check at all
 * and returned a misleading, payable-looking HTTP 200 (subtotal 0, shipping-only total). The two
 * paths disagreed on whether an empty cart was valid.</p>
 *
 * <p>Both {@code previewOrder} and {@code submitOrder} now raise this exception for an empty cart, so
 * the paths agree, and it is mapped to a single controlled HTTP 400 (Bad Request) — consistent with
 * the controller's other client-precondition failures (missing {@code zip}, non-positive quantity).
 * The frontend renders this as a non-payable empty-cart state.</p>
 *
 * <p>The {@link ResponseStatus} annotation maps the exception to HTTP 400 if it propagates out of a
 * Spring MVC handler; the rest layer also maps it explicitly via {@code @RestControllerAdvice} (which
 * takes precedence and returns a sanitized JSON body). Being a {@link RuntimeException}, it also
 * triggers a clean rollback if raised inside the {@code @Transactional submitOrder} boundary.</p>
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class EmptyCartException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a detail message.
     *
     * @param message the detail message (e.g., the member id whose cart is empty)
     */
    public EmptyCartException(String message) {
        super(message);
    }
}
