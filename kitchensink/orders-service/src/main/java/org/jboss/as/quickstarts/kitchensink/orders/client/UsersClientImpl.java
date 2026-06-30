package org.jboss.as.quickstarts.kitchensink.orders.client;

import org.jboss.as.quickstarts.kitchensink.orders.dto.MemberTierDto;
import org.jboss.as.quickstarts.kitchensink.orders.exception.MemberNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
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

    private final RestClient restClient;

    public UsersClientImpl(@Value("${users.base-url}") String usersBaseUrl) {
        this.restClient = RestClient.builder().baseUrl(usersBaseUrl).build();
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
