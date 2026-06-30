package org.jboss.as.quickstarts.kitchensink.orders.client;

import java.time.Duration;
import org.jboss.as.quickstarts.kitchensink.orders.dto.MemberTierDto;
import org.jboss.as.quickstarts.kitchensink.orders.exception.MemberNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
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

    private final RestClient restClient;

    public UsersClientImpl(@Value("${users.base-url}") String usersBaseUrl) {
        // Configure explicit connect/read timeouts on the underlying request factory so a slow or
        // unreachable users-service fails fast rather than hanging discount/order orchestration and
        // exhausting request threads. No retry/cache/circuit-breaker is added (minimal-change).
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl(usersBaseUrl)
                .requestFactory(requestFactory)
                .build();
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
}
