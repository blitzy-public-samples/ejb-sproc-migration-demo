package org.jboss.as.quickstarts.kitchensink.orders.client;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import org.jboss.as.quickstarts.kitchensink.orders.exception.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException;

/**
 * Anti-corruption-layer HTTP client for marketplace-service. One of only two classes
 * in orders-service permitted to use RestTemplate directly. Contract Authority (§0.6.2)
 * governs request/response shapes and error mappings.
 */
@Component
public class MarketplaceClient {

    private final RestTemplate restTemplate;
    private final String marketplaceBaseUrl;

    public MarketplaceClient(RestTemplate restTemplate,
                             @Value("${services.marketplace.base-url}") String marketplaceBaseUrl) {
        this.restTemplate = restTemplate;
        this.marketplaceBaseUrl = marketplaceBaseUrl;
    }

    /**
     * Contract 1 - unit price. Response body is a bare JSON number (e.g. 9.1692).
     * 404 -> InventoryNotFoundException; 5xx -> ServiceUnavailableException.
     */
    public BigDecimal getUnitPrice(Long productId, Long vendorId, int qty) {
        String url = marketplaceBaseUrl + "/api/products/{productId}/price?vendorId={vendorId}&qty={qty}";
        try {
            return restTemplate.getForObject(url, BigDecimal.class, productId, vendorId, qty);
        } catch (HttpClientErrorException.NotFound e) {
            throw new InventoryNotFoundException(
                    "No inventory found for productId=" + productId + ", vendorId=" + vendorId, e);
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "marketplace-service unavailable while fetching unit price for productId=" + productId, e);
        }
    }

    /**
     * GAP-1 + GAP-2 - quote (best vendor + unit price + product weight) in one round-trip.
     * Deserialized into the local ProductQuoteDto (never the producer ProductQuoteResponse).
     * 404 -> InventoryNotFoundException; 5xx -> ServiceUnavailableException.
     */
    public ProductQuoteDto getQuote(Long productId, int qty) {
        String url = marketplaceBaseUrl + "/api/products/{productId}/quote?qty={qty}";
        try {
            return restTemplate.getForObject(url, ProductQuoteDto.class, productId, qty);
        } catch (HttpClientErrorException.NotFound e) {
            throw new InventoryNotFoundException(
                    "No quote available for productId=" + productId + " (qty=" + qty + ")", e);
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "marketplace-service unavailable while fetching quote for productId=" + productId, e);
        }
    }
}
