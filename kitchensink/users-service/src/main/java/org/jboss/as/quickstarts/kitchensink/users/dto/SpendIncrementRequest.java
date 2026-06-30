package org.jboss.as.quickstarts.kitchensink.users.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Typed, validated request body for the internal spend-increment endpoint
 * {@code POST /api/members/{id}/spend} (GAP-3, AAP &sect;0.6.6).
 *
 * <p>Replaces the previous raw {@code Map<String, BigDecimal>} body, which accepted a missing
 * {@code amount} as zero and accepted negative/arbitrary amounts with no validation -- permitting
 * spend tampering. Both fields are now mandatory and the amount must be strictly positive, so
 * malformed/missing/negative input is rejected with HTTP 400 before any mutation occurs.</p>
 *
 * <ul>
 *   <li>{@code orderId} -- the originating order's globally-unique id; used by
 *       {@code MemberSpendService} as the IDEMPOTENCY KEY so a retried/duplicated post-commit
 *       increment is applied at most once.</li>
 *   <li>{@code amount} -- the order total to add to the member's {@code total_spend}; must be
 *       present and {@code > 0}.</li>
 * </ul>
 *
 * <p>Mutable POJO (no-arg constructor + setters) for robust Jackson binding.</p>
 */
public class SpendIncrementRequest {

    /** Originating order id; the idempotency key. Must be present. */
    @NotNull(message = "orderId is required")
    private Long orderId;

    /** Amount to add to the member's total_spend. Must be present and strictly positive. */
    @NotNull(message = "amount is required")
    @Positive(message = "amount must be greater than zero")
    private BigDecimal amount;

    public SpendIncrementRequest() {
    }

    public SpendIncrementRequest(Long orderId, BigDecimal amount) {
        this.orderId = orderId;
        this.amount = amount;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
