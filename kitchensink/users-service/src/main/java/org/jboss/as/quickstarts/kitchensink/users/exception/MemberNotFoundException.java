package org.jboss.as.quickstarts.kitchensink.users.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by users-service when a member id cannot be found.
 *
 * <p>Replaces the monolith's member-lookup not-found path in
 * {@code MemberResourceRESTService.lookupMemberById} (which threw
 * {@code new WebApplicationException(Response.Status.NOT_FOUND)}). It is also the
 * users-service producer side of Contract 2 ({@code 404 -> MemberNotFoundException}).
 * Thrown from REST handlers such as {@code GET /api/members/{id}/tier},
 * {@code GET /api/members/{id}}, and {@code POST /api/members/{id}/spend} when the id
 * is unknown.</p>
 *
 * <p>This is users-service's OWN copy &mdash; a distinct type from the identically shaped
 * exceptions in marketplace-service and orders-service; the types are never shared
 * across the service boundary. {@code @ResponseStatus(HttpStatus.NOT_FOUND)} makes
 * Spring MVC return 404 when this exception propagates out of a users-service
 * controller.</p>
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
