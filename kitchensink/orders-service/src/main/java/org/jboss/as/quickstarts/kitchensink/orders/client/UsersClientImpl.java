package org.jboss.as.quickstarts.kitchensink.orders.client;

import java.math.BigDecimal;
import java.time.Duration;
import org.jboss.as.quickstarts.kitchensink.orders.dto.MemberSpendIncrementRequest;
import org.jboss.as.quickstarts.kitchensink.orders.dto.MemberTierDto;
import org.jboss.as.quickstarts.kitchensink.orders.exception.MemberNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Default HTTP implementation of {@link UsersClient} (orders-service &rarr; users-service).
 * Talks only HTTP+JSON via {@link RestClient}; holds no business logic and imports no
 * users-service class (boundary rule, AAP &sect;0.7.2). Implements Contract 2 (Tier),
 * AAP &sect;0.6.2.
 */
@Component
public class UsersClientImpl implements UsersClient {

    /**
     * Connect timeout for outbound users-service calls. Caps how long the client waits to establish
     * the TCP connection so that a down or unreachable users-service fails fast instead of hanging an
     * orders-service request thread during discount/order orchestration (resilience/security
     * hardening, AAP &sect;0.6.3).
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Read (socket) timeout for outbound users-service calls. Bounds how long the client waits for
     * the tier response once the request is sent, limiting the time a discount calculation or
     * {@code @Transactional submitOrder} can be blocked by a slow users-service &mdash; including the
     * time a database transaction stays open while awaiting the member tier (AAP &sect;0.6.3).
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** Header carrying the shared service-to-service secret for users-service {@code /internal/**}. */
    private static final String API_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient restClient;

    /**
     * @param usersBaseUrl   base URL of users-service including its {@code /users} context-path, e.g.
     *                       {@code http://localhost:8083/users} (property {@code users.base-url}).
     * @param internalApiKey shared service-to-service secret (property {@code internal.api-key},
     *                       sourced from {@code INTERNAL_API_KEY}). Sent as the {@code X-Internal-Api-Key}
     *                       header so users-service's {@code InternalApiKeyFilter} authorizes the
     *                       lifetime-spend write ({@code POST /internal/members/{id}/total-spend}). When
     *                       blank (e.g. in tests, where this client is mocked) no header is attached, and
     *                       the Contract 2 tier read ({@code /api/members/**}) is unaffected because that
     *                       endpoint is not gated.
     */
    public UsersClientImpl(@Value("${users.base-url}") String usersBaseUrl,
                           @Value("${internal.api-key:}") String internalApiKey) {
        // Configure explicit connect/read timeouts on the underlying request factory so a slow or
        // unreachable users-service fails fast rather than hanging discount/order orchestration and
        // exhausting request threads. No retry/cache/circuit-breaker is added (minimal-change).
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(usersBaseUrl)
                .requestFactory(requestFactory);
        // users-service guards /internal/** with a fail-closed shared-secret filter; attach the key as
        // a default header so the lifetime-spend write is authenticated. Only add it when configured to
        // avoid sending an empty header (and so the ungated Contract 2 tier read is unaffected).
        if (internalApiKey != null && !internalApiKey.isBlank()) {
            builder.defaultHeader(API_KEY_HEADER, internalApiKey);
        }
        this.restClient = builder.build();
    }

    @Override
    public String getMemberTier(Long memberId) {
        try {
            MemberTierDto response = restClient.get()
                    .uri("/api/members/{memberId}/tier", memberId)
                    .retrieve()
                    .body(MemberTierDto.class);
            if (response == null || response.getTier() == null) {
                throw new ServiceUnavailableException(
                        "users-service returned an empty tier for member " + memberId);
            }
            return response.getTier();
        } catch (HttpClientErrorException.NotFound e) {
            throw new MemberNotFoundException("Member not found: " + memberId, e);
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "users-service returned an error fetching the tier for member " + memberId);
        } catch (RestClientException e) {
            throw new ServiceUnavailableException(
                    "users-service is unavailable fetching the tier for member " + memberId, e);
        }
    }

    @Override
    public void incrementMemberTotalSpend(Long memberId, BigDecimal amount) {
        try {
            restClient.post()
                    .uri("/internal/members/{memberId}/total-spend", memberId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new MemberSpendIncrementRequest(amount))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // 404 -> users-service has no such member; surface as the domain not-found exception so the
            // @Transactional submitOrder rolls back rather than committing an order against a ghost member.
            throw new MemberNotFoundException("Member not found: " + memberId, e);
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "users-service returned an error incrementing total_spend for member " + memberId);
        } catch (RestClientException e) {
            throw new ServiceUnavailableException(
                    "users-service is unavailable incrementing total_spend for member " + memberId, e);
        }
    }
}
