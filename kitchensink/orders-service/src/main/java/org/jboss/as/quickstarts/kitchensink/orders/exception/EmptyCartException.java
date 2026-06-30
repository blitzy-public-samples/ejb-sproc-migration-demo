package org.jboss.as.quickstarts.kitchensink.orders.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by orders-service when an order is orchestrated for a member whose draft cart is empty.
 *
 * <p>Reproduces the {@code process_order} stored-procedure guard
 * (db/02_stored_procedures.sql L226-232) that raised {@code ERRCODE 'P0004'} ("Cart is empty for
 * member %") before any order row was created. In the monolith this aborted the whole procedure;
 * the migrated orders-service must reject an empty-cart submit/preview the same way rather than
 * silently producing a zero-subtotal order with only the shipping floor applied.</p>
 *
 * <p>{@code @ResponseStatus(HttpStatus.BAD_REQUEST)} makes Spring MVC return 400 when this
 * exception propagates out of an orders-service controller — the natural HTTP mapping for a
 * client-side precondition failure (the caller submitted with nothing in the cart).</p>
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class EmptyCartException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmptyCartException(String message) {
        super(message);
    }
}
