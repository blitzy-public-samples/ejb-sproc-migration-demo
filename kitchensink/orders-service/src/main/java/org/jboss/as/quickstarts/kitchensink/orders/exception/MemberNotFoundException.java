package org.jboss.as.quickstarts.kitchensink.orders.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown on the CONSUMER side of Contract 2 (Tier) when the orders-service
 * {@code UsersClient} receives HTTP 404 from users-service for a member-tier lookup
 * ({@code GET {users.base-url}/api/members/{memberId}/tier}), meaning the member does not exist.
 *
 * <p>This is the Java replacement for the PL/pgSQL member-not-found conditions formerly emitted
 * by the stored procedures (see {@code db/02_stored_procedures.sql}):
 * {@code USING ERRCODE = 'P0002'} in {@code apply_customer_discount} and
 * {@code USING ERRCODE = 'P0003'} in {@code process_order}. Because the procedures are never
 * invoked after migration, a missing member is now signalled with this unchecked exception during
 * discount / order orchestration.</p>
 *
 * <p><strong>Contract nuance (AAP &sect;0.6.2):</strong> orders-service is the PRODUCER of
 * Contract 3 ({@code GET /internal/members/{memberId}/spend?days=}). A 404 in THAT path is NOT
 * mapped to this exception — the users-service caller treats a 404 as {@link java.math.BigDecimal#ZERO}.
 * This exception is used ONLY on the consumer side (Contract 2 tier lookup).</p>
 *
 * <p>The {@link ResponseStatus} annotation maps the exception to HTTP 404 if it propagates out of a
 * Spring MVC handler; the rest layer may also map it explicitly via {@code @RestControllerAdvice}
 * (which would take precedence). Being a {@link RuntimeException}, it also triggers a clean rollback
 * if raised inside a {@code @Transactional} boundary.</p>
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class MemberNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a detail message.
     *
     * @param message the detail message (e.g., the failing tier URL and the member id)
     */
    public MemberNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a detail message and underlying cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause (may be {@code null})
     */
    public MemberNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
