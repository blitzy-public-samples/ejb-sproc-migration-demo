package org.jboss.as.quickstarts.kitchensink.service;

/**
 * Thrown when an order is previewed or submitted for a member whose draft cart is empty.
 *
 * <p>Replaces the {@code EJBException} that the {@code process_order} stored procedure surfaced via
 * {@code RAISE EXCEPTION ... USING ERRCODE = 'P0004'} (db/02_stored_procedures.sql). Unchecked so it
 * can propagate out of {@code @Service} methods without checked-exception plumbing.</p>
 *
 * <p>HTTP mapping: {@code rest/RestExceptionHandler} maps this to <strong>400 BAD REQUEST</strong>
 * (note this differs from the 404 NOT FOUND of the not-found exceptions, because an empty cart is a
 * client request error rather than a missing resource).</p>
 */
public class EmptyCartException extends RuntimeException {

    /**
     * Creates an exception with a detail message describing the empty-cart condition.
     *
     * @param message the detail message (typically identifies the offending member)
     */
    public EmptyCartException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a detail message and an underlying cause.
     *
     * @param message the detail message (typically identifies the offending member)
     * @param cause   the underlying cause (saved for later retrieval by {@link #getCause()})
     */
    public EmptyCartException(String message, Throwable cause) {
        super(message, cause);
    }
}
