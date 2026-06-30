package org.jboss.as.quickstarts.kitchensink.orders.client;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException;

/**
 * Anti-corruption-layer HTTP client for users-service. One of only two classes in
 * orders-service permitted to use RestTemplate directly. Contract Authority (§0.6.2)
 * governs request/response shapes and error mappings.
 */
@Component
public class UsersClient {

    private final RestTemplate restTemplate;
    private final String usersBaseUrl;

    public UsersClient(RestTemplate restTemplate,
                       @Value("${services.users.base-url}") String usersBaseUrl) {
        this.restTemplate = restTemplate;
        this.usersBaseUrl = usersBaseUrl;
    }

    /**
     * Contract 2 - member loyalty tier. Body {"tier":"GOLD"} (BRONZE/SILVER/GOLD/PLATINUM).
     * 404 -> rethrow Spring's HttpClientErrorException.NotFound (MemberNotFoundException is
     * users-service-owned and intentionally NOT created here); 5xx -> ServiceUnavailableException.
     */
    public String getMemberTier(Long memberId) {
        String url = usersBaseUrl + "/api/members/{memberId}/tier";
        try {
            MemberTierDto dto = restTemplate.getForObject(url, MemberTierDto.class, memberId);
            return dto != null ? dto.tier() : null;
        } catch (HttpClientErrorException.NotFound e) {
            // Cross-domain boundary rule (§0.6.6 / Contract 2): a 404 means the member does not
            // exist. MemberNotFoundException belongs to users-service and is deliberately not
            // duplicated here, so surface the not-found as Spring's typed runtime failure.
            throw e;
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "users-service unavailable while fetching tier for memberId=" + memberId, e);
        }
    }

    /**
     * GAP-3 - post-commit member spend increment. Called by OrderService AFTER the order
     * transaction commits (never inside the @Transactional boundary); an eventually-consistent,
     * idempotency-friendly write, with the nightly tier recalculation as the reconciling backstop.
     * 5xx -> ServiceUnavailableException.
     */
    public void incrementMemberSpend(Long memberId, BigDecimal amount) {
        String url = usersBaseUrl + "/api/members/{memberId}/spend";
        try {
            restTemplate.postForObject(url, Map.of("amount", amount), Void.class, memberId);
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "users-service unavailable while incrementing spend for memberId=" + memberId, e);
        }
    }
}
