package org.jboss.as.quickstarts.kitchensink.orders.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.jboss.as.quickstarts.kitchensink.orders.exception.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Default HTTP implementation of {@link MarketplaceClient} (orders-service &rarr; marketplace-service).
 * Talks only HTTP+JSON via {@link RestClient}; holds no business logic and imports no
 * marketplace-service class (boundary rule, AAP &sect;0.7.2). Implements Contract 1 (Pricing)
 * plus the vendor-ranking read (AAP &sect;0.6.2).
 */
@Component
public class MarketplaceClientImpl implements MarketplaceClient {

    /**
     * Connect timeout for outbound marketplace-service calls. Caps how long the client waits to
     * establish the TCP connection so that a down or unreachable marketplace-service fails fast
     * instead of hanging an orders-service request thread (resilience/security hardening, AAP
     * &sect;0.6.3).
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Read (socket) timeout for outbound marketplace-service calls. Bounds how long the client waits
     * for the response once the request is sent, limiting the time an order preview/submit can be
     * blocked by a slow marketplace-service &mdash; and, for the {@code @Transactional submitOrder}
     * path, the time the database transaction stays open while awaiting pricing (AAP &sect;0.6.3).
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;

    public MarketplaceClientImpl(@Value("${marketplace.base-url}") String marketplaceBaseUrl) {
        // Configure explicit connect/read timeouts on the underlying request factory so a slow or
        // unreachable marketplace-service fails fast rather than hanging order preview/submit paths
        // and exhausting request threads. No retry/cache/circuit-breaker is added (minimal-change).
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl(marketplaceBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public Long selectBestVendor(Long productId, int qty) {
        try {
            List<VendorOption> vendors = restClient.get()
                    .uri("/api/products/{productId}/vendors?qty={qty}", productId, qty)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<VendorOption>>() {});
            if (vendors == null || vendors.isEmpty()) {
                return null;
            }
            return vendors.get(0).vendorId();
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "marketplace-service returned an error selecting a vendor for product " + productId);
        } catch (RestClientException e) {
            throw new ServiceUnavailableException(
                    "marketplace-service is unavailable selecting a vendor for product " + productId, e);
        }
    }

    @Override
    public BigDecimal getPrice(Long productId, Long vendorId, int qty) {
        try {
            return restClient.get()
                    .uri("/api/products/{productId}/price?vendorId={vendorId}&qty={qty}", productId, vendorId, qty)
                    .retrieve()
                    .body(BigDecimal.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new InventoryNotFoundException(
                    "No inventory found for product " + productId + " and vendor " + vendorId, e);
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "marketplace-service returned an error pricing product " + productId);
        } catch (RestClientException e) {
            throw new ServiceUnavailableException(
                    "marketplace-service is unavailable pricing product " + productId, e);
        }
    }

    /**
     * Minimal projection of a single entry in the marketplace vendor-ranking response. The
     * marketplace endpoint returns a JSON array of ranked vendor objects (vendorId, vendorName,
     * unitPrice, fulfillmentRating, avgShippingDays); orders-service only needs the id of the
     * top-ranked entry, so all other properties are ignored. Declared as a private nested type
     * to avoid creating a shared cross-service DTO (boundary rule).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VendorOption(Long vendorId) {
    }
}
