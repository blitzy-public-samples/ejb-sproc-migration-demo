package org.jboss.as.quickstarts.kitchensink.orders.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Typed, validated request body for {@code POST /api/orders/cart/{memberId}}.
 *
 * <p>Replaces the previous raw {@code Map<String, Object>} body, whose blind
 * {@code ((Number) body.get(...))} casts produced a {@code ClassCastException} -> HTTP 500 for
 * malformed JSON (e.g. string values). With this DTO, Jackson deserialization failures surface as
 * {@code HttpMessageNotReadableException} (handled as 400 by the controller) and constraint
 * violations surface as {@code MethodArgumentNotValidException} (also 400) -- so all malformed or
 * invalid input yields a clean 400 instead of a 500.</p>
 *
 * <p>Mutable POJO (no-arg constructor + setters) for robust Jackson binding.</p>
 */
public class AddToCartRequest {

    /** Product to add to the draft cart. Must be present. */
    @NotNull(message = "productId is required")
    private Long productId;

    /**
     * Quantity to add. Must be present and strictly positive -- this preserves the legacy
     * "quantity must be greater than zero" rule (formerly an explicit {@code quantity <= 0} check)
     * via Bean Validation.
     */
    @NotNull(message = "quantity is required")
    @Positive(message = "quantity must be greater than zero")
    private Integer quantity;

    public AddToCartRequest() {
    }

    public AddToCartRequest(Long productId, Integer quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
