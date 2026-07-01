package org.jboss.as.quickstarts.kitchensink.users.client;

import java.math.BigDecimal;

/**
 * Cross-domain HTTP gateway from users-service to orders-service implementing
 * Contract 3 (Spend). This is the ONLY legal cross-domain channel out of
 * users-service (anti-corruption layer / gateway pattern — AAP &sect;0.3.3, &sect;0.7.2).
 *
 * <p>It lets {@code service.TierRecalculationService} read a member's 90-day
 * rolling spend (used to recompute loyalty tiers) WITHOUT importing any
 * orders-service class &mdash; all communication is over HTTP.</p>
 *
 * <p>Declared as an interface so integration tests (TierRecalculationIT) can
 * stub it without standing up orders-service.</p>
 *
 * <p>Contract 3 (AAP &sect;0.6.2):
 * {@code GET {orders.base-url}/internal/members/{memberId}/spend?days=90} returns
 * {@code 200 {"totalSpend":2750.00}}. The implementation maps a {@code 404}
 * response to {@link java.math.BigDecimal#ZERO} and a {@code 5xx} or transport
 * failure to
 * {@link org.jboss.as.quickstarts.kitchensink.users.exception.ServiceUnavailableException}.</p>
 */
public interface OrdersClient {

    /**
     * Returns the member's 90-day rolling spend (sum of CONFIRMED order totals
     * over the last 90 days), obtained from orders-service over HTTP per
     * Contract 3.
     *
     * @param memberId the member identifier (the {@code member} PK, a {@code BIGSERIAL})
     * @return the 90-day rolling spend; {@link java.math.BigDecimal#ZERO} when
     *         orders-service reports no spend on record for the member (HTTP 404)
     * @throws org.jboss.as.quickstarts.kitchensink.users.exception.ServiceUnavailableException
     *         if orders-service responds with a 5xx status or is unreachable
     */
    BigDecimal getNinetyDaySpend(Long memberId);
}
