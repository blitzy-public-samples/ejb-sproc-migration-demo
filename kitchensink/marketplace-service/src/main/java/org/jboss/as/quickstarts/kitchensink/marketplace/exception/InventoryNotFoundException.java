package org.jboss.as.quickstarts.kitchensink.marketplace.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a product or its vendor_inventory row cannot be found.
 *
 * <p>Replaces the stored procedure's {@code RAISE EXCEPTION ... ERRCODE 'P0001'}
 * (see db/02_stored_procedures.sql -> calculate_price) and the legacy
 * {@code EJBException} runtime path. Maps to HTTP 404 via {@code @ResponseStatus}
 * so that when it propagates out of the REST layer Spring MVC returns
 * 404 Not Found automatically.</p>
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class InventoryNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InventoryNotFoundException(String message) {
        super(message);
    }

    public InventoryNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
