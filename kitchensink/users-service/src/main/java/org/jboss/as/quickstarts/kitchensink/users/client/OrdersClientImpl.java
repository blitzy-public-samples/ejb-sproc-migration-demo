package org.jboss.as.quickstarts.kitchensink.users.client;

import org.jboss.as.quickstarts.kitchensink.users.dto.MemberSpendDto;
import org.jboss.as.quickstarts.kitchensink.users.exception.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;

/**
 * Default {@link OrdersClient} implementation &mdash; the thin HTTP gateway that
 * realizes Contract 3 (Spend) against orders-service.
 *
 * <p>Externalizes the 90-day spend read that the monolith's
 * {@code TierRecalculationService} performed as a native SQL sub-select on the
 * {@code orders} table. Per the cross-domain boundary rule (AAP &sect;0.7.2) this
 * class communicates with orders-service ONLY over HTTP and references ONLY
 * users-service-local types ({@link MemberSpendDto},
 * {@link ServiceUnavailableException}).</p>
 *
 * <p>Error mapping (Contract 3, AAP &sect;0.6.2): {@code 200} &rarr; totalSpend;
 * {@code 404} &rarr; {@link BigDecimal#ZERO}; {@code 5xx}/transport failure &rarr;
 * {@link ServiceUnavailableException}.</p>
 */
@Component
public class OrdersClientImpl implements OrdersClient {

    /**
     * Rolling window (in days) that drives loyalty-tier recalculation
     * (AAP &sect;0.6.1/&sect;0.6.5); sent as the {@code days} query parameter.
     */
    private static final int TIER_WINDOW_DAYS = 90;

    private final RestClient restClient;

    /**
     * @param ordersBaseUrl base URL of orders-service including its {@code /orders}
     *                      context-path, e.g. {@code http://localhost:8082/orders}
     *                      (property {@code orders.base-url}).
     */
    public OrdersClientImpl(@Value("${orders.base-url}") String ordersBaseUrl) {
        this.restClient = RestClient.builder().baseUrl(ordersBaseUrl).build();
    }

    @Override
    public BigDecimal getNinetyDaySpend(Long memberId) {
        try {
            MemberSpendDto response = restClient.get()
                    .uri("/internal/members/{memberId}/spend?days={days}", memberId, TIER_WINDOW_DAYS)
                    .retrieve()
                    .body(MemberSpendDto.class);

            if (response == null || response.getTotalSpend() == null) {
                return BigDecimal.ZERO;
            }
            return response.getTotalSpend();
        } catch (HttpClientErrorException.NotFound ex) {
            // Contract 3: 404 -> the member has no spend on record -> treat as zero (do NOT throw).
            return BigDecimal.ZERO;
        } catch (HttpServerErrorException ex) {
            // Contract 3: 5xx -> orders-service is failing.
            throw new ServiceUnavailableException(
                    "orders-service returned " + ex.getStatusCode()
                            + " for 90-day spend of member " + memberId);
        } catch (RestClientException ex) {
            // Transport/connection failure (ResourceAccessException) or any other RestClient error.
            throw new ServiceUnavailableException(
                    "orders-service unreachable while reading 90-day spend of member " + memberId, ex);
        }
    }
}
