package org.jboss.as.quickstarts.kitchensink.users.client;

import org.jboss.as.quickstarts.kitchensink.users.dto.MemberSpendDto;
import org.jboss.as.quickstarts.kitchensink.users.exception.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;

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

    /**
     * Connect timeout for outbound orders-service calls. Caps how long the client waits to establish
     * the TCP connection so that a down or unreachable orders-service fails fast instead of hanging
     * the nightly tier-recalculation scheduler thread (and any request thread) that invokes this
     * gateway (resilience/security hardening, AAP &sect;0.6.5).
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Read (socket) timeout for outbound orders-service calls. Bounds how long the client waits for
     * the spend response once the request is sent, preventing a slow orders-service from blocking the
     * per-member tier-recalculation fan-out (AAP &sect;0.6.5) and exhausting scheduler/request threads.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** Header carrying the shared service-to-service secret for orders-service {@code /internal/**}. */
    private static final String API_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient restClient;

    /**
     * @param ordersBaseUrl  base URL of orders-service including its {@code /orders}
     *                       context-path, e.g. {@code http://localhost:8082/orders}
     *                       (property {@code orders.base-url}).
     * @param internalApiKey shared service-to-service secret (property {@code internal.api-key},
     *                       sourced from {@code INTERNAL_API_KEY}). Sent as the
     *                       {@code X-Internal-Api-Key} header so orders-service's
     *                       {@code InternalApiKeyFilter} authorizes the Contract 3 spend read.
     *                       When blank (e.g. in tests, where this client is mocked) no header is
     *                       attached.
     */
    public OrdersClientImpl(@Value("${orders.base-url}") String ordersBaseUrl,
                            @Value("${internal.api-key:}") String internalApiKey) {
        // Configure explicit connect/read timeouts on the underlying request factory so a slow or
        // unreachable orders-service fails fast rather than hanging the tier-recalculation scheduler
        // threads and exhausting resources. No retry/cache/circuit-breaker is added (minimal-change).
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(ordersBaseUrl)
                .requestFactory(requestFactory);
        // orders-service guards /internal/** with a fail-closed shared-secret filter; attach the key
        // as a default header so every Contract 3 spend read is authenticated. Only add it when
        // configured to avoid sending an empty header.
        if (internalApiKey != null && !internalApiKey.isBlank()) {
            builder.defaultHeader(API_KEY_HEADER, internalApiKey);
        }
        this.restClient = builder.build();
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
