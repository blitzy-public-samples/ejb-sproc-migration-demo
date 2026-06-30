package org.jboss.as.quickstarts.kitchensink.orders.client;

/**
 * Cross-domain HTTP gateway from orders-service to users-service. One of the two legal
 * cross-domain channels out of orders-service (anti-corruption layer / gateway pattern —
 * AAP &sect;0.3.3, &sect;0.7.2); the other is {@link MarketplaceClient}.
 *
 * <p>Externalizes the in-process tier read the monolith's {@code DiscountService.getMemberTier}
 * previously performed via JPQL against the {@code Member} entity (now owned by users-service
 * and MUST NOT be imported). All communication is over HTTP+JSON.</p>
 *
 * <p>Declared as an interface so integration tests (DiscountServiceIT, OrderServiceIT) can stub
 * it (e.g. with {@code @MockBean}) while {@code UsersClientImpl} is exercised against
 * {@code MockRestServiceServer}/WireMock.</p>
 *
 * <p>Implements Contract 2 (Tier): {@code GET {users.base-url}/api/members/{memberId}/tier}
 * &rarr; 200 {@code {"tier":"GOLD"}} (AAP &sect;0.6.2).</p>
 */
public interface UsersClient {

    /**
     * Returns the loyalty tier for a member per Contract 2 (Tier):
     * {@code GET {users.base-url}/api/members/{memberId}/tier} &rarr; 200 {@code {"tier":"GOLD"}}.
     *
     * @param memberId the member identifier
     * @return the tier token: one of {@code BRONZE}, {@code SILVER}, {@code GOLD}, {@code PLATINUM}
     * @throws org.jboss.as.quickstarts.kitchensink.orders.exception.MemberNotFoundException
     *         if users-service returns 404 (no such member)
     * @throws org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException
     *         if users-service responds with a 5xx status or is unreachable
     */
    String getMemberTier(Long memberId);
}
