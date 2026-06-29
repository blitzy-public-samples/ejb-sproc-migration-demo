package org.jboss.as.quickstarts.kitchensink.rest;

import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.jboss.as.quickstarts.kitchensink.service.EmptyCartException;
import org.jboss.as.quickstarts.kitchensink.service.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.service.MemberNotFoundException;

/**
 * Centralized REST exception handling, replacing the inline JAX-RS {@code Response}/
 * {@code WebApplicationException} construction that the legacy resources performed.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): the legacy
 * {@code MemberResourceRESTService} built error responses inline — a 400 field-error map from bean
 * validation, a 409 for duplicate email, and a 404 for missing members. That cross-cutting concern is
 * consolidated here as a {@code @RestControllerAdvice} so every controller shares one consistent HTTP
 * error contract. The duplicate-email 409 remains inline in {@code MemberResourceRESTService} (there
 * is no dedicated duplicate-email exception type to map here).</p>
 *
 * <p>Status mapping (preserving the legacy contract and AAP requirements):</p>
 * <ul>
 *   <li>{@link MethodArgumentNotValidException} (from {@code @Valid}) &rarr; 400 with a
 *       field-name&rarr;message map, mirroring the legacy {@code ConstraintViolationException} handling.</li>
 *   <li>{@link MemberNotFoundException} (≙ stored-proc {@code P0002}/{@code P0003}) &rarr; 404.</li>
 *   <li>{@link InventoryNotFoundException} (≙ stored-proc {@code P0001}) &rarr; 404.</li>
 *   <li>{@link EmptyCartException} (≙ stored-proc {@code P0004}) &rarr; 400.</li>
 * </ul>
 */
@RestControllerAdvice
public class RestExceptionHandler {

    /**
     * Maps bean-validation failures to a 400 response whose body is a map of field name to violation
     * message — the exact shape the legacy resource produced from {@code ConstraintViolation}s.
     *
     * @param ex the validation exception raised by {@code @Valid}
     * @return 400 with a field-to-message map
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    /**
     * Maps member-not-found to 404, replacing the legacy {@code WebApplicationException(NOT_FOUND)}.
     *
     * @param ex the member-not-found exception
     * @return 404 with an {@code error} message
     */
    @ExceptionHandler(MemberNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleMemberNotFound(MemberNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(ex.getMessage()));
    }

    /**
     * Maps inventory-not-found (a vendor not stocking a product) to 404.
     *
     * @param ex the inventory-not-found exception
     * @return 404 with an {@code error} message
     */
    @ExceptionHandler(InventoryNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleInventoryNotFound(InventoryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(ex.getMessage()));
    }

    /**
     * Maps an empty-cart order submission to 400.
     *
     * @param ex the empty-cart exception
     * @return 400 with an {@code error} message
     */
    @ExceptionHandler(EmptyCartException.class)
    public ResponseEntity<Map<String, String>> handleEmptyCart(EmptyCartException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(ex.getMessage()));
    }

    private Map<String, String> errorBody(String message) {
        Map<String, String> body = new HashMap<>();
        body.put("error", message);
        return body;
    }
}
