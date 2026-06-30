package org.jboss.as.quickstarts.kitchensink.orders.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown during order orchestration when no eligible vendor can satisfy a cart line item, i.e. the
 * marketplace best-vendor selection ({@code GET {marketplace.base-url}/api/products/{id}/best-vendor})
 * returns no vendor for the requested product/quantity.
 *
 * <p><strong>Why this exists (review finding M1 — data integrity).</strong> The monolith's
 * {@code process_order} stored procedure priced the selected vendor for every cart line and FAILED
 * the whole order when a line could not be satisfied; it never created a partial order by silently
 * dropping items. The migrated {@code OrderService} previously {@code continue}d past a {@code null}
 * vendor, which silently omitted the line from preview/submit and could persist a partial order
 * (under-charging the customer). This exception restores the faithful all-or-nothing behavior: a cart
 * line with no eligible vendor aborts the preview/submit instead of being skipped.</p>
 *
 * <p>Mapped to {@code HTTP 409 CONFLICT}: the request is well-formed but cannot be fulfilled in the
 * current catalog/inventory state (no in-stock vendor for the line), which is a conflict with server
 * state rather than a client input error (400) or a missing resource (404). The status is applied via
 * {@link ResponseStatus} and also mapped explicitly by {@code OrdersRestExceptionHandler}
 * ({@code @RestControllerAdvice}), which takes precedence; the two REST handlers in
 * {@code OrderResourceRESTService} re-throw this exception ahead of their generic catch so it reaches
 * the advice instead of being flattened to {@code 500}.</p>
 *
 * <p>Being a {@link RuntimeException}, it also triggers a clean rollback when raised inside the
 * {@code @Transactional submitOrder} boundary, so no partial order is committed.</p>
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class NoEligibleVendorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a detail message.
     *
     * @param message the detail message (e.g., the product id and requested quantity that could not
     *                be satisfied)
     */
    public NoEligibleVendorException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a detail message and underlying cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause (may be {@code null})
     */
    public NoEligibleVendorException(String message, Throwable cause) {
        super(message, cause);
    }
}
