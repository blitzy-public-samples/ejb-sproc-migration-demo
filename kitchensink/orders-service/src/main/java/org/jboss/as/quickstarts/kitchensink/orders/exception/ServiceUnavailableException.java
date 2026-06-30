package org.jboss.as.quickstarts.kitchensink.orders.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Signals that a downstream service could not satisfy a cross-service request because it
 * responded with a {@code 5xx} status or was unreachable (transport-level failure).
 *
 * <p>Raised by BOTH orders-service HTTP gateways:
 * <ul>
 *   <li>{@code org.jboss.as.quickstarts.kitchensink.orders.client.MarketplaceClientImpl}
 *       — Contract 1 (Pricing) to marketplace-service
 *       ({@code GET {marketplace.base-url}/api/products/{productId}/price?vendorId=&qty=}).</li>
 *   <li>{@code org.jboss.as.quickstarts.kitchensink.orders.client.UsersClientImpl}
 *       — Contract 2 (Tier) to users-service
 *       ({@code GET {users.base-url}/api/members/{memberId}/tier}).</li>
 * </ul>
 *
 * <p><strong>Transactional role (AAP &sect;0.6.3):</strong> when raised inside the
 * {@code @Transactional submitOrder} path, this {@link RuntimeException} propagates so Spring rolls
 * the order transaction back cleanly — no partial order is committed.</p>
 *
 * <p>This is a SEPARATE class from the users-service
 * {@code org.jboss.as.quickstarts.kitchensink.users.exception.ServiceUnavailableException}
 * (intentional duplication — no shared module, upholding the boundary rule).</p>
 *
 * <p>The {@link ResponseStatus} annotation maps the exception to HTTP 503 (Service Unavailable) if
 * it propagates out of a Spring MVC handler; the rest layer may also map it explicitly via
 * {@code @RestControllerAdvice} (which would take precedence).</p>
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class ServiceUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a human-readable message describing the failed downstream call.
     *
     * @param message detail message (e.g., the failing URL and HTTP status)
     */
    public ServiceUnavailableException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and the underlying cause (e.g., a transport-level
     * {@code IOException}/{@code RestClientException} raised when the peer is unreachable).
     *
     * @param message detail message
     * @param cause   the underlying cause (may be {@code null})
     */
    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
