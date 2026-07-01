package org.jboss.as.quickstarts.kitchensink.users.client;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import org.jboss.as.quickstarts.kitchensink.users.exception.ServiceUnavailableException;

/**
 * Anti-corruption-layer HTTP client for orders-service. The ONLY class in users-service
 * permitted to use RestTemplate directly. Contract Authority (AAP 0.6.2, Contract 3 - spend)
 * governs the request/response shape and error mappings.
 *
 * <p>Invoked by TierRecalculationService during the per-member calculation phase of the
 * nightly tier recalculation - strictly OUTSIDE any @Transactional boundary.</p>
 */
@Component
public class OrdersClient {

    /**
     * Header carrying the shared service-to-service token. orders-service's {@code /internal/**}
     * endpoints (including the Contract-3 spend read) are access-controlled, so this trusted internal
     * caller presents the token on every spend lookup; otherwise orders-service returns 401.
     */
    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestTemplate restTemplate;
    private final String ordersBaseUrl;
    private final String internalServiceToken;

    public OrdersClient(RestTemplate restTemplate,
                        @Value("${services.orders.base-url}") String ordersBaseUrl,
                        @Value("${security.internal.token}") String internalServiceToken) {
        this.restTemplate = restTemplate;
        this.ordersBaseUrl = ordersBaseUrl;
        this.internalServiceToken = internalServiceToken;
    }

    /**
     * Contract 3 - member spend over the trailing {@code days} window. Response body is
     * {@code {"totalSpend":2750.00}}, deserialized into the local {@link MemberSpendDto}.
     *
     * <p>The request presents the {@value #INTERNAL_TOKEN_HEADER} header because the orders-service
     * Contract-3 endpoint lives under its access-controlled {@code /internal/**} path.</p>
     *
     * <p>Error mapping (Contract Authority - binding): a downstream <b>404</b> means the
     * member has no order history, so this returns {@link BigDecimal#ZERO} (NOT an exception
     * - this differs deliberately from the orders/marketplace clients, which throw on 404);
     * a downstream <b>5xx</b> throws {@link ServiceUnavailableException}.</p>
     */
    public BigDecimal getMemberSpend(Long memberId, int days) {
        String url = ordersBaseUrl + "/internal/members/{memberId}/spend?days={days}";

        HttpHeaders headers = new HttpHeaders();
        headers.set(INTERNAL_TOKEN_HEADER, internalServiceToken);

        try {
            ResponseEntity<MemberSpendDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), MemberSpendDto.class, memberId, days);
            MemberSpendDto dto = response.getBody();
            return dto != null ? dto.totalSpend() : BigDecimal.ZERO;
        } catch (HttpClientErrorException.NotFound e) {
            return BigDecimal.ZERO;
        } catch (HttpClientErrorException e) {
            // Non-404 4xx (e.g. 401/403 on shared-token misconfiguration): the Contract Authority
            // (§0.6.2, Contract 3) maps only 404 (-> ZERO, preserved above) + 5xx, so fail fast as
            // 503 rather than letting a raw HttpClientErrorException escape this client.
            throw new ServiceUnavailableException(
                    "orders-service returned unexpected " + e.getStatusCode()
                            + " while fetching spend for memberId=" + memberId, e);
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "orders-service unavailable while fetching spend for memberId=" + memberId, e);
        } catch (ResourceAccessException e) {
            // Connect/read timeout (bounded by RestTemplateConfig) or other I/O failure: peer slow or
            // unreachable. Fail fast as 503; TierRecalculationService catches this per-member and skips
            // that member so the nightly run continues.
            throw new ServiceUnavailableException(
                    "orders-service unreachable/timed out while fetching spend for memberId=" + memberId, e);
        }
    }
}
