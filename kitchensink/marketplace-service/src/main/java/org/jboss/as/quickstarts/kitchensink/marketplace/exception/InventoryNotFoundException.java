package org.jboss.as.quickstarts.kitchensink.marketplace.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by the marketplace pricing logic when no vendor_inventory row exists
 * for a given (product, vendor) pair.
 *
 * <p>This is the producer-side counterpart of the PL/pgSQL
 * {@code RAISE EXCEPTION ... USING ERRCODE = 'P0001'} formerly emitted inside
 * the {@code calculate_price} stored procedure
 * (see {@code db/02_stored_procedures.sql}). Reimplementing that procedure in
 * Java means the missing-inventory condition is now signalled with this
 * unchecked exception.</p>
 *
 * <p>The {@link ResponseStatus} annotation maps the exception to HTTP 404
 * (Not Found), satisfying the producer side of Contract 1 (Pricing):
 * {@code GET /api/products/{productId}/price?vendorId=&qty=} returns 404 when
 * inventory is missing. A {@code @RestControllerAdvice} in the rest layer may
 * also map it explicitly; the annotation guarantees the 404 either way.</p>
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class InventoryNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a pre-built message.
     *
     * @param message the detail message
     */
    public InventoryNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a descriptive message built from the product
     * and vendor identifiers, mirroring the original SQL message text
     * ("No vendor inventory found for product % vendor %").
     *
     * @param productId the product id with no matching vendor inventory
     * @param vendorId  the vendor id with no matching inventory for the product
     */
    public InventoryNotFoundException(Long productId, Long vendorId) {
        super(String.format("No vendor inventory found for product %d vendor %d", productId, vendorId));
    }
}
