package org.jboss.as.quickstarts.kitchensink.users.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by users-service when a downstream service call (orders-service member
 * spend) fails with an HTTP 5xx, indicating the peer service is temporarily
 * unavailable.
 *
 * <p>Introduced by the monolith-to-microservices decomposition: the tier
 * recalculation that once read order spend in-process (see the legacy
 * {@code TierRecalculationService} native {@code recalculate_customer_tiers()} call)
 * now invokes the cross-service HTTP endpoint {@code OrdersClient.getMemberSpend}.
 * Per the Contract Authority, Contract 3 maps {@code 5xx -> ServiceUnavailableException}.</p>
 *
 * <p>This is users-service's OWN copy &mdash; distinct from the identically named exception
 * in orders-service; the two are never shared across the service boundary.
 * {@code @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)} makes Spring MVC return 503
 * when this exception propagates out of a users-service controller.</p>
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
