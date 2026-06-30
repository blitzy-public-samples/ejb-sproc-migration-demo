package org.jboss.as.quickstarts.kitchensink.users.client;

import java.math.BigDecimal;

/**
 * Local consumer DTO for the Contract-3 member-spend response ({"totalSpend":2750.00}).
 * Intentionally distinct from orders-service's producer MemberSpendResponse so that no
 * producer type ever crosses the bounded-context boundary — users-service consumes the
 * spend value over HTTP only. Jackson binds the JSON field "totalSpend" to this record's
 * component by name; OrdersClient.getMemberSpend(...) unwraps it via the totalSpend() accessor.
 */
public record MemberSpendDto(BigDecimal totalSpend) {
}
