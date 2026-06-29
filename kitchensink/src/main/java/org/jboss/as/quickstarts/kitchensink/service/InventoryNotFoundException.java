package org.jboss.as.quickstarts.kitchensink.service;

/**
 * Thrown when no product / vendor-inventory combination exists for a requested price calculation.
 *
 * Replaces the EJBException that the calculate_price stored procedure surfaced via
 * RAISE EXCEPTION ... USING ERRCODE = 'P0001' (db/02_stored_procedures.sql). Unchecked so it can
 * propagate out of @Service methods without checked-exception plumbing.
 *
 * HTTP mapping: rest/RestExceptionHandler maps this to 404 NOT FOUND.
 */
public class InventoryNotFoundException extends RuntimeException {

    public InventoryNotFoundException(String message) {
        super(message);
    }

    public InventoryNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
