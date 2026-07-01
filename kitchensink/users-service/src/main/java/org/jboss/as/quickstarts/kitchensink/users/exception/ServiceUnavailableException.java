package org.jboss.as.quickstarts.kitchensink.users.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Signals that the <strong>orders-service</strong> could not satisfy a Contract 3 (90-day spend)
 * request because it responded with a {@code 5xx} status or was unreachable (transport-level
 * failure).
 *
 * <p>Raised by {@code org.jboss.as.quickstarts.kitchensink.users.client.OrdersClientImpl} while it
 * services the nightly tier-recalculation job in
 * {@code org.jboss.as.quickstarts.kitchensink.users.service.TierRecalculationService}. The job
 * calls {@code GET {orders.base-url}/internal/members/{memberId}/spend?days=90} once per member
 * (Contract 3).</p>
 *
 * <p>Contract 3 error mapping (AAP &sect;0.6.2):</p>
 * <ul>
 *   <li>{@code 200} &rarr; parsed {@code MemberSpendDto} (success; not this exception).</li>
 *   <li>{@code 404} &rarr; the caller treats the member's spend as {@link java.math.BigDecimal#ZERO}
 *       (NOT this exception).</li>
 *   <li>{@code 5xx} / transport failure &rarr; this exception.</li>
 * </ul>
 *
 * <p>Extends {@link RuntimeException} so it propagates without checked-exception plumbing and, when
 * thrown inside a Spring {@code @Transactional} boundary, triggers a clean rollback. The
 * {@link ResponseStatus} mapping yields HTTP {@code 503 Service Unavailable} if the exception ever
 * surfaces through a Spring MVC controller, although its primary role is internal to the scheduled
 * tier job.</p>
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class ServiceUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a human-readable message describing the failed orders-service call.
     *
     * @param message detail message (e.g., the failing URL and HTTP status)
     */
    public ServiceUnavailableException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and the underlying cause (e.g., a transport-level
     * {@code IOException}/{@code RestClientException} raised when orders-service is unreachable).
     *
     * @param message detail message
     * @param cause   the underlying cause (may be {@code null})
     */
    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
