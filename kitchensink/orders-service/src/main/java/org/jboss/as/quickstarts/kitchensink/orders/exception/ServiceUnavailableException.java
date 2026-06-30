package org.jboss.as.quickstarts.kitchensink.orders.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by orders-service when a downstream service call (marketplace price/quote
 * or users member-tier) fails with an HTTP 5xx, indicating the peer service is
 * temporarily unavailable.
 *
 * <p>Introduced by the monolith-to-microservices decomposition: calls that were
 * once in-process (and could not fail over the network) are now cross-service HTTP
 * calls made by the client/ components. Per the Contract Authority, every contract
 * maps {@code 5xx -> ServiceUnavailableException}.</p>
 *
 * <p>This is orders-service's OWN copy &mdash; distinct from the identically named
 * exception in users-service; the two are never shared across the service boundary.
 * {@code @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)} makes Spring MVC return 503
 * when this exception propagates out of an orders-service controller.</p>
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class ServiceUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
