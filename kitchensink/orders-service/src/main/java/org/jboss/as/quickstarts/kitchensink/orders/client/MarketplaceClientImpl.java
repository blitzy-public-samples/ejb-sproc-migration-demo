package org.jboss.as.quickstarts.kitchensink.orders.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import org.jboss.as.quickstarts.kitchensink.orders.exception.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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

    private final RestClient restClient;

    public MarketplaceClientImpl(@Value("${marketplace.base-url}") String marketplaceBaseUrl) {
        this.restClient = RestClient.builder().baseUrl(marketplaceBaseUrl).build();
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
