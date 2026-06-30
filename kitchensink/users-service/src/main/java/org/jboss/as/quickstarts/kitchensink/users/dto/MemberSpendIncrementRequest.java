package org.jboss.as.quickstarts.kitchensink.users.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Producer DTO (users-service) for the lifetime-spend write contract
 * ({@code POST /internal/members/{id}/total-spend}).
 *
 * <p>Realizes the Source-A {@code process_order} side-effect that increments a member's lifetime
 * {@code total_spend} by the order SUBTOTAL (AAP &sect;0.6.1 / &sect;0.7.3 — Source A overrides the
 * Source-B SQL which added the total). Because the {@code orders} domain no longer shares a
 * compile-time dependency on the {@code member} entity (boundary rule, AAP &sect;0.7.2), orders-service
 * performs this increment over HTTP through its {@code UsersClient}; this DTO is the request body.</p>
 *
 * <p>Bean Validation rejects a missing or non-positive amount with HTTP 400 (the controller binds it
 * with {@code @Valid}), so a malformed increment can never silently corrupt {@code total_spend}.
 * Orders-service declares its own consumer copy of this shape, so the duplication is intentional and
 * upholds the no-shared-module boundary rule.</p>
 *
 * @param amount the positive amount (order subtotal) to add to the member's lifetime spend
 */
public record MemberSpendIncrementRequest(@NotNull @Positive BigDecimal amount) {
}
