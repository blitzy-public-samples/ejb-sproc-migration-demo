package org.jboss.as.quickstarts.kitchensink.orders.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by orders-service when a downstream users-service tier lookup reports that
 * the requested member does not exist (HTTP 404).
 *
 * <p>This is the orders-service consumer side of Contract 2
 * ({@code 404 -> MemberNotFoundException}; see Contract Authority §0.6.2). The
 * {@code UsersClient} maps a downstream {@code GET /api/members/{memberId}/tier} 404
 * onto this typed exception so that orders-service business logic (for example
 * {@code DiscountService} during discount calculation) receives a meaningful domain
 * failure rather than a transport-layer {@code HttpClientErrorException}. It
 * conceptually preserves the monolith behavior in which a missing-member tier lookup
 * surfaced as a runtime failure (the original {@code DiscountService.getMemberTier}
 * used {@code getSingleResult()}, which raised {@code NoResultException}).</p>
 *
 * <p>This is orders-service's OWN copy &mdash; a distinct type from the identically
 * named exception in users-service; the two are never shared across the service
 * boundary (no producer type ever crosses the bounded context).
 * {@code @ResponseStatus(HttpStatus.NOT_FOUND)} makes Spring MVC return 404 when this
 * exception propagates out of an orders-service controller.</p>
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class MemberNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MemberNotFoundException(String message) {
        super(message);
    }

    public MemberNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
