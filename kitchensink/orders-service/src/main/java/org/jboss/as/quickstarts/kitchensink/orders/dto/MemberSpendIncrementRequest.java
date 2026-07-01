package org.jboss.as.quickstarts.kitchensink.orders.dto;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Consumer DTO (orders-service) for the lifetime-spend write contract
 * ({@code POST {users.base-url}/internal/members/{id}/total-spend}).
 *
 * <p>This is the request body orders-service sends to users-service to realize the Source-A
 * {@code process_order} side-effect that increments a member's lifetime {@code total_spend} by the
 * order SUBTOTAL (AAP &sect;0.6.1 / &sect;0.7.3 — Source A overrides the Source-B SQL which added the
 * total). Because orders-service owns no {@code Member} entity and the cross-domain boundary rule
 * (AAP &sect;0.7.2) forbids it from writing the {@code member} table directly, the increment is
 * performed over HTTP through {@code UsersClient}; this record is serialized to JSON
 * ({@code {"amount": 49.99}}) as that call's body.</p>
 *
 * <p>This is the CONSUMER copy of the shape; users-service declares its own producer record
 * ({@code MemberSpendIncrementRequest} with {@code @NotNull @Positive} validation). The duplication
 * is intentional and upholds the no-shared-module boundary rule — server-side Bean Validation on the
 * producer is the authoritative guard, so this consumer copy carries no validation annotations.</p>
 *
 * @param amount the positive amount (order subtotal) to add to the member's lifetime spend
 */
public record MemberSpendIncrementRequest(BigDecimal amount) implements Serializable {
}
