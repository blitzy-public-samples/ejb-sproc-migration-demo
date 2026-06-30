package org.jboss.as.quickstarts.kitchensink.orders.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by orders-service when a downstream marketplace price/quote call reports
 * that the requested product/vendor inventory does not exist (HTTP 404).
 *
 * <p>Conceptually replaces the monolith path where the {@code calculate_price}
 * stored procedure raised {@code ERRCODE 'P0001'} (see db/02_stored_procedures.sql)
 * and surfaced as an {@code EJBException}. In the decomposed system the
 * {@code MarketplaceClient} maps a downstream 404 to this typed exception
 * (Contract 1: {@code 404 -> InventoryNotFoundException}).</p>
 *
 * <p>This is orders-service's OWN copy — a distinct type from marketplace-service's
 * identically named exception; the two are never shared across the service boundary.
 * {@code @ResponseStatus(HttpStatus.NOT_FOUND)} makes Spring MVC return 404 when this
 * exception propagates out of an orders-service controller.</p>
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
