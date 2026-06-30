package org.jboss.as.quickstarts.kitchensink.users.client;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
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

    private final RestTemplate restTemplate;
    private final String ordersBaseUrl;

    public OrdersClient(RestTemplate restTemplate,
                        @Value("${services.orders.base-url}") String ordersBaseUrl) {
        this.restTemplate = restTemplate;
        this.ordersBaseUrl = ordersBaseUrl;
    }

    /**
     * Contract 3 - member spend over the trailing {@code days} window. Response body is
     * {@code {"totalSpend":2750.00}}, deserialized into the local {@link MemberSpendDto}.
     *
     * <p>Error mapping (Contract Authority - binding): a downstream <b>404</b> means the
     * member has no order history, so this returns {@link BigDecimal#ZERO} (NOT an exception
     * - this differs deliberately from the orders/marketplace clients, which throw on 404);
     * a downstream <b>5xx</b> throws {@link ServiceUnavailableException}.</p>
     */
    public BigDecimal getMemberSpend(Long memberId, int days) {
        String url = ordersBaseUrl + "/internal/members/{memberId}/spend?days={days}";
        try {
            MemberSpendDto dto = restTemplate.getForObject(url, MemberSpendDto.class, memberId, days);
            return dto != null ? dto.totalSpend() : BigDecimal.ZERO;
        } catch (HttpClientErrorException.NotFound e) {
            return BigDecimal.ZERO;
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "orders-service unavailable while fetching spend for memberId=" + memberId, e);
        }
    }
}
