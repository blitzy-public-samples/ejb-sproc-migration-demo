package org.jboss.as.quickstarts.kitchensink.rest;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.jboss.as.quickstarts.kitchensink.service.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.service.MemberNotFoundException;
import org.jboss.as.quickstarts.kitchensink.service.EmptyCartException;

/**
 * Centralizes the HTTP error-response contracts that were previously built inline in
 * {@code MemberResourceRESTService} (and the other JAX-RS resources) using
 * jakarta.ws.rs Response/WebApplicationException. Migrated to Spring MVC as a global
 * {@code @RestControllerAdvice}.
 *
 * <p>The status codes and JSON body shapes produced here must match the pre-migration
 * contract exactly, because the PHP storefront and the integration tests depend on them:
 * <ul>
 *   <li>{@link MethodArgumentNotValidException} &rarr; <strong>400</strong> with a
 *       {@code {field : message}} map (the former {@code createViolationResponse}).</li>
 *   <li>{@link DuplicateEmailException} &rarr; <strong>409</strong> with the exact body
 *       {@code {"email":"Email taken"}} (the former {@code ValidationException} &rarr; CONFLICT).</li>
 *   <li>{@link InventoryNotFoundException} / {@link MemberNotFoundException} &rarr;
 *       <strong>404</strong> with the exception message (precise replacements for the
 *       former generic failure paths).</li>
 *   <li>{@link EmptyCartException} &rarr; <strong>400</strong> with the exception message.</li>
 * </ul>
 *
 * <p>Spring auto-discovers this advice via the {@code @SpringBootApplication} component scan
 * (base package {@code org.jboss.as.quickstarts.kitchensink}); no manual registration is needed.
 * No catch-all {@code Exception} handler is declared on purpose, so unmapped errors continue to
 * surface as Spring's default 500 rather than being masked.
 */
@RestControllerAdvice
public class RestExceptionHandler {

    /**
     * Handles bean-validation failures raised when a {@code @Valid @RequestBody Member} payload
     * violates its constraints. Reproduces the former {@code createViolationResponse} shape: a
     * map whose keys are the offending property names (e.g. {@code name}, {@code email},
     * {@code phoneNumber}) and whose values are the corresponding constraint messages.
     *
     * @param ex the validation exception carrying the binding result with field errors
     * @return {@code 400 BAD_REQUEST} with a {@code Map<String,String>} of field-to-message entries
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
            .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    /**
     * Handles the duplicate-email condition thrown by {@code MemberResourceRESTService.createMember}
     * when a member with the same email already exists. Reproduces the former
     * {@code ValidationException} &rarr; CONFLICT contract exactly.
     *
     * @param ex the duplicate-email exception (only its type is significant here)
     * @return {@code 409 CONFLICT} with the exact body {@code {"email":"Email taken"}}
     */
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<Map<String, String>> handleDuplicateEmail(DuplicateEmailException ex) {
        Map<String, String> body = new HashMap<>();
        body.put("email", "Email taken");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Handles the absence of a product / vendor-inventory combination during price calculation.
     * Replaces the {@code P0001} {@code RAISE EXCEPTION} formerly surfaced by the
     * {@code calculate_price} stored procedure (and the JAX-RS getPrice 404 path).
     *
     * @param ex the inventory-not-found exception
     * @return {@code 404 NOT_FOUND} with the exception message as a plain string body
     */
    @ExceptionHandler(InventoryNotFoundException.class)
    public ResponseEntity<String> handleInventoryNotFound(InventoryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    /**
     * Handles a reference to a member that does not exist. Replaces the {@code P0002}/{@code P0003}
     * {@code RAISE EXCEPTION} conditions formerly surfaced by the {@code apply_customer_discount}
     * and {@code process_order} stored procedures.
     *
     * @param ex the member-not-found exception
     * @return {@code 404 NOT_FOUND} with the exception message as a plain string body
     */
    @ExceptionHandler(MemberNotFoundException.class)
    public ResponseEntity<String> handleMemberNotFound(MemberNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    /**
     * Handles an order preview/submit attempted against an empty draft cart. Replaces the
     * {@code P0004} {@code RAISE EXCEPTION} formerly surfaced by the {@code process_order} stored
     * procedure. An empty cart is treated as a client request error (400) rather than a missing
     * resource (404).
     *
     * @param ex the empty-cart exception
     * @return {@code 400 BAD_REQUEST} with the exception message as a plain string body
     */
    @ExceptionHandler(EmptyCartException.class)
    public ResponseEntity<String> handleEmptyCart(EmptyCartException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }

    /**
     * Thrown by MemberResourceRESTService.createMember when a member with the same email
     * already exists. Mapped to HTTP 409 with body {"email":"Email taken"}.
     *
     * <p>It lives here as a nested {@code public static} type (rather than a separate top-level
     * file) so that the 409 contract stays co-located with the advice that enforces it, and so the
     * controller in this same package can reference it as
     * {@code RestExceptionHandler.DuplicateEmailException} without an import.
     */
    public static class DuplicateEmailException extends RuntimeException {

        public DuplicateEmailException() {
            super("Email taken");
        }
    }
}
