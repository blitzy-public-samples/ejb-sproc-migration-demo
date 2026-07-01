package org.jboss.as.quickstarts.kitchensink.orders.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown on the CONSUMER side of Contract 1 (Pricing) when the orders-service
 * {@code MarketplaceClient} receives HTTP 404 from marketplace-service for a price lookup
 * ({@code GET {marketplace.base-url}/api/products/{productId}/price?vendorId=&qty=}),
 * meaning no vendor_inventory row exists for the requested (product, vendor) pair.
 *
 * <p>This is the Java replacement for the PL/pgSQL
 * {@code RAISE EXCEPTION ... USING ERRCODE = 'P0001'} formerly emitted by the
 * {@code calculate_price} stored procedure (see {@code db/02_stored_procedures.sql}).
 * Because the stored procedures are never invoked after migration, the missing-inventory
 * condition is now signalled with this unchecked exception during order orchestration
 * (preview / submit).</p>
 *
 * <p>This is a SEPARATE class from the marketplace-service producer-side
 * {@code org.jboss.as.quickstarts.kitchensink.marketplace.exception.InventoryNotFoundException}
 * (intentional duplication — no shared module, upholding the boundary rule).</p>
 *
 * <p>The {@link ResponseStatus} annotation maps the exception to HTTP 404 if it propagates
 * out of a Spring MVC handler; the rest layer may also map it explicitly via
 * {@code @RestControllerAdvice} (which would take precedence). Being a {@link RuntimeException},
 * it also triggers a clean rollback if raised inside a {@code @Transactional} boundary.</p>
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class InventoryNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a detail message.
     *
     * @param message the detail message (e.g., the failing pricing URL and the product/vendor ids)
     */
    public InventoryNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a detail message and underlying cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause (may be {@code null})
     */
    public InventoryNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
