package org.jboss.as.quickstarts.kitchensink.orders.client;

/**
 * Local consumer DTO for the Contract-2 member-tier response ({"tier":"GOLD"}).
 * Intentionally distinct from users-service's producer MemberTierResponse so that
 * no producer type ever crosses the bounded-context boundary (orders consumes the
 * tier over HTTP only). Deserialized by Jackson from the tier endpoint and unwrapped
 * by UsersClient.getMemberTier(...) via the tier() accessor.
 */
public record MemberTierDto(String tier) {
}
