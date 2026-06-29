package org.jboss.as.quickstarts.kitchensink.service;

/**
 * Thrown when a referenced member does not exist.
 *
 * <p>Replaces the member-not-found errors the original PL/pgSQL stored procedures surfaced via
 * {@code RAISE EXCEPTION} (see {@code kitchensink/db/02_stored_procedures.sql}):
 * <ul>
 *   <li>{@code apply_customer_discount} &rarr; {@code ERRCODE 'P0002'} ("Member not found")</li>
 *   <li>{@code process_order} &rarr; {@code ERRCODE 'P0003'} ("Member not found")</li>
 * </ul>
 * In the Spring Boot migration this logic lives in the application tier, so the same
 * condition is signalled by throwing this exception instead of invoking a database function.
 *
 * <p>Unchecked (extends {@link RuntimeException}) so it can propagate out of {@code @Service}
 * methods without checked-exception plumbing. It is thrown by
 * {@code DiscountService} ({@code calculateDiscount}, {@code getMemberTier}) and by
 * {@code OrderService} ({@code orchestrateOrder}, {@code submitOrder}) when the supplied
 * member id does not resolve to an existing member.
 *
 * <p>HTTP mapping: {@code rest/RestExceptionHandler} maps this to {@code 404 NOT FOUND}.
 */
public class MemberNotFoundException extends RuntimeException {

    public MemberNotFoundException(String message) {
        super(message);
    }

    public MemberNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
