package org.jboss.as.quickstarts.kitchensink.users.dto;

/**
 * Producer DTO for GET /api/members/{id}/tier (Contract 2 — Tier).
 * Serializes to {"tier":"GOLD"} (tier values: BRONZE/SILVER/GOLD/PLATINUM).
 * NOT shared across the service boundary — orders-service defines its own local MemberTierDto.
 */
public record MemberTierResponse(String tier) {
}
