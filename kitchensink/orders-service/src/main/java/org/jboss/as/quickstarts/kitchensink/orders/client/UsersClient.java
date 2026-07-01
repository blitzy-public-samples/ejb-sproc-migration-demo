package org.jboss.as.quickstarts.kitchensink.orders.client;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import org.jboss.as.quickstarts.kitchensink.orders.exception.MemberNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException;

/**
 * Anti-corruption-layer HTTP client for users-service. One of only two classes in
 * orders-service permitted to use RestTemplate directly. Contract Authority (§0.6.2)
 * governs request/response shapes and error mappings.
 */
@Component
public class UsersClient {

    /**
     * Header carrying the shared service-to-service token. Both the member-spend mutation endpoint and
     * the member-tier read on users-service are now access-controlled (CRITICAL security findings):
     * member-scoped reads require authentication and ownership, and a trusted peer service authenticates
     * with this shared token to obtain cross-member (SERVICE) access. orders-service is such a trusted
     * caller, so it presents the token on every users-service request (tier read and spend increment).
     */
    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestTemplate restTemplate;
    private final String usersBaseUrl;
    private final String internalServiceToken;

    public UsersClient(RestTemplate restTemplate,
                       @Value("${services.users.base-url}") String usersBaseUrl,
                       @Value("${security.internal.token}") String internalServiceToken) {
        this.restTemplate = restTemplate;
        this.usersBaseUrl = usersBaseUrl;
        this.internalServiceToken = internalServiceToken;
    }

    /**
     * Contract 2 - member loyalty tier. Body {"tier":"GOLD"} (BRONZE/SILVER/GOLD/PLATINUM).
     * 404 -> MemberNotFoundException (orders-service's OWN local type); 5xx -> ServiceUnavailableException.
     *
     * <p>ACCESS CONTROL: the users-service tier endpoint is member-scoped and access-controlled; this
     * client is a trusted peer service, so the request carries the shared service token
     * ({@value #INTERNAL_TOKEN_HEADER}) to authenticate as a SERVICE caller (permitted to read any
     * member's tier). The call is sent via {@code exchange} with an explicit header entity rather than
     * {@code getForObject} so the token travels with the request.</p>
     */
    public String getMemberTier(Long memberId) {
        String url = usersBaseUrl + "/api/members/{memberId}/tier";

        HttpHeaders headers = new HttpHeaders();
        headers.set(INTERNAL_TOKEN_HEADER, internalServiceToken);

        try {
            MemberTierDto dto = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), MemberTierDto.class, memberId).getBody();
            return dto != null ? dto.tier() : null;
        } catch (HttpClientErrorException.NotFound e) {
            // Contract Authority (§0.6.2, Contract 2): a 404 means the member does not exist;
            // translate it into the orders-service-LOCAL MemberNotFoundException so downstream
            // orders logic (e.g. DiscountService) receives a typed domain failure. The local
            // exception keeps the cross-domain boundary intact - users-service's identically
            // named type is never imported (§0.7.2 no-shared-classes rule).
            throw new MemberNotFoundException(
                    "Member not found in users-service for memberId=" + memberId, e);
        } catch (HttpClientErrorException e) {
            // Non-404 4xx (e.g. 401/403 on shared-token misconfiguration): the Contract Authority
            // (§0.6.2, Contract 2) maps only 404 + 5xx, so fail fast as 503 rather than leaking a
            // raw 500 out of the orders-service controller.
            throw new ServiceUnavailableException(
                    "users-service returned unexpected " + e.getStatusCode()
                            + " while fetching tier for memberId=" + memberId, e);
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "users-service unavailable while fetching tier for memberId=" + memberId, e);
        } catch (ResourceAccessException e) {
            // Connect/read timeout (bounded by RestTemplateConfig) or other I/O failure: peer slow or
            // unreachable. Fail fast as 503 rather than blocking indefinitely or leaking a raw 500.
            throw new ServiceUnavailableException(
                    "users-service unreachable/timed out while fetching tier for memberId=" + memberId, e);
        }
    }

    /**
     * GAP-3 (§0.6.6) - post-commit member spend increment. Called by OrderService AFTER the order
     * transaction commits (never inside the @Transactional boundary); an eventually-consistent write
     * (the order may commit even if this increment fails).
     *
     * <p>IDEMPOTENCY: the just-persisted {@code orderId} is sent as an idempotency key. orders.id is
     * the globally-unique primary key of the orders table, so users-service applies each order's
     * spend AT MOST ONCE even if this post-commit call is retried or duplicated. This -- not the
     * nightly tier recalculation (which recomputes only the loyalty tier from the trailing-90-day
     * window and never rewrites the lifetime total_spend column) -- is the durable correctness
     * guarantee for total_spend.</p>
     *
     * <p>ACCESS CONTROL: this is an internal, data-mutating endpoint, so the request carries the
     * shared service token ({@value #INTERNAL_TOKEN_HEADER}); unauthenticated callers are rejected by
     * users-service. 5xx -> ServiceUnavailableException.</p>
     *
     * @param memberId the member whose lifetime spend is incremented
     * @param orderId  the persisted order id, used by users-service as the idempotency key
     * @param amount   the order total to add to the member's total_spend
     */
    public void incrementMemberSpend(Long memberId, Long orderId, BigDecimal amount) {
        String url = usersBaseUrl + "/api/members/{memberId}/spend";

        // LinkedHashMap (not Map.of) keeps a stable JSON field order and tolerates the typed values.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("amount", amount);

        HttpHeaders headers = new HttpHeaders();
        headers.set(INTERNAL_TOKEN_HEADER, internalServiceToken);

        try {
            restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Void.class, memberId);
        } catch (HttpClientErrorException e) {
            // Any 4xx on this post-commit increment (e.g. 401/403 token misconfig, 400/404) is outside
            // the Contract Authority. GAP-3 is eventually-consistent (the order already committed), so
            // fail fast as 503 - the documented spend-POST failure signal - instead of leaking a raw
            // 500; idempotency by orderId keeps a later retry safe.
            throw new ServiceUnavailableException(
                    "users-service returned unexpected " + e.getStatusCode()
                            + " while incrementing spend for memberId=" + memberId, e);
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "users-service unavailable while incrementing spend for memberId=" + memberId, e);
        } catch (ResourceAccessException e) {
            // Connect/read timeout (bounded by RestTemplateConfig) or other I/O failure: peer slow or
            // unreachable. Fail fast as 503; the order already committed and the increment is idempotent.
            throw new ServiceUnavailableException(
                    "users-service unreachable/timed out while incrementing spend for memberId=" + memberId, e);
        }
    }
}
