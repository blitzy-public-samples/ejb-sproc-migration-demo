package org.jboss.as.quickstarts.kitchensink.orders.client;

import java.math.BigDecimal;

/**
 * Cross-domain HTTP gateway from orders-service to users-service. One of the two legal
 * cross-domain channels out of orders-service (anti-corruption layer / gateway pattern —
 * AAP &sect;0.3.3, &sect;0.7.2); the other is {@link MarketplaceClient}.
 *
 * <p>Externalizes the in-process tier read the monolith's {@code DiscountService.getMemberTier}
 * previously performed via JPQL against the {@code Member} entity (now owned by users-service
 * and MUST NOT be imported), and the {@code member.total_spend} increment that {@code process_order}
 * performed via {@code UPDATE member ...} (now a write contract to users-service). All communication
 * is over HTTP+JSON.</p>
 *
 * <p>Declared as an interface so integration tests (DiscountServiceIT, OrderServiceIT) can stub
 * it (e.g. with {@code @MockitoBean}) while {@code UsersClientImpl} is exercised against
 * {@code MockRestServiceServer}/WireMock.</p>
 *
 * <p>Implements Contract 2 (Tier): {@code GET {users.base-url}/api/members/{memberId}/tier}
 * &rarr; 200 {@code {"tier":"GOLD"}} (AAP &sect;0.6.2), plus the lifetime-spend write contract
 * ({@code POST {users.base-url}/internal/members/{memberId}/total-spend}) that realizes the Source-A
 * {@code process_order} {@code total_spend} side-effect (AAP &sect;0.6.1).</p>
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

    /**
     * Increments a member's lifetime {@code total_spend} by the given amount (the order SUBTOTAL),
     * realizing the Source-A {@code process_order} side-effect over HTTP:
     * {@code POST {users.base-url}/internal/members/{memberId}/total-spend} with body
     * {@code {"amount": N}}.
     *
     * <p>The {@code member} table is owned by users-service; the cross-domain boundary rule (AAP
     * &sect;0.7.2) forbids orders-service from writing it directly, so this write is externalized
     * through users-service's internal API. The call carries the shared {@code X-Internal-Api-Key}
     * header so users-service's {@code InternalApiKeyFilter} authorizes it. orders-service invokes this
     * as the FINAL step of {@code submitOrder} (inside its {@code @Transactional} boundary), so a
     * failure rolls the order back cleanly (AAP &sect;0.6.3); it is NEVER called from the preview path.</p>
     *
     * @param memberId the member whose lifetime spend is incremented
     * @param amount   the positive amount (order subtotal) to add
     * @throws org.jboss.as.quickstarts.kitchensink.orders.exception.MemberNotFoundException
     *         if users-service returns 404 (no such member)
     * @throws org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException
     *         if users-service responds with a 5xx status or is unreachable
     */
    void incrementMemberTotalSpend(Long memberId, BigDecimal amount);
}
