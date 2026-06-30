package org.jboss.as.quickstarts.kitchensink.orders.dto;

import java.math.BigDecimal;

/**
 * Producer DTO for GET /internal/members/{id}/spend?days= (Contract 3 — Spend).
 * Serializes to {"totalSpend":2750.00}.
 * NOT shared across the service boundary — users-service defines its own local MemberSpendDto.
 */
public record MemberSpendResponse(BigDecimal totalSpend) {
}
