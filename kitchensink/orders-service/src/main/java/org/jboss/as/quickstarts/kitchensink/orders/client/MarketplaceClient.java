package org.jboss.as.quickstarts.kitchensink.orders.client;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
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
        } catch (HttpClientErrorException e) {
            // Non-404 4xx (e.g. 401/403 on shared-token misconfiguration): the Contract Authority
            // (§0.6.2) maps only 404 + 5xx, so fail fast as 503 rather than leaking a raw 500.
            throw new ServiceUnavailableException(
                    "marketplace-service returned unexpected " + e.getStatusCode()
                            + " while fetching unit price for productId=" + productId, e);
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "marketplace-service unavailable while fetching unit price for productId=" + productId, e);
        } catch (ResourceAccessException e) {
            // Connect/read timeout (bounded by RestTemplateConfig) or other I/O failure: the peer is
            // slow or unreachable. Fail fast as 503 rather than blocking indefinitely or leaking a 500.
            throw new ServiceUnavailableException(
                    "marketplace-service unreachable/timed out while fetching unit price for productId="
                            + productId, e);
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
        } catch (HttpClientErrorException e) {
            // Non-404 4xx (e.g. 401/403 on shared-token misconfiguration): the Contract Authority
            // (§0.6.2) maps only 404 + 5xx, so fail fast as 503 rather than leaking a raw 500.
            throw new ServiceUnavailableException(
                    "marketplace-service returned unexpected " + e.getStatusCode()
                            + " while fetching quote for productId=" + productId, e);
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "marketplace-service unavailable while fetching quote for productId=" + productId, e);
        } catch (ResourceAccessException e) {
            // Connect/read timeout (bounded by RestTemplateConfig) or other I/O failure: the peer is
            // slow or unreachable. Fail fast as 503 rather than blocking indefinitely or leaking a 500.
            throw new ServiceUnavailableException(
                    "marketplace-service unreachable/timed out while fetching quote for productId="
                            + productId, e);
        }
    }

    /**
     * Lightweight product-existence probe. Calls the marketplace catalog read
     * {@code GET {marketplace.base-url}/api/products/{productId}}, which returns 200 when the
     * product exists and 404 when it does not (legacy catalog semantics preserved by
     * marketplace-service's {@code ProductResourceRESTService#getProduct}).
     *
     * <p>Used by {@code OrderService.addToCart} to reject an unknown {@code productId} with a clean
     * 404 BEFORE a draft row is persisted, instead of letting the value trip the
     * {@code order_draft_items.product_id -> products(id)} foreign key and surface as a generic
     * HTTP 500 ({@code DataIntegrityViolationException}). This is the proactive cross-service
     * validation the QA finding recommends: orders-service cannot import marketplace types, so the
     * check is performed over HTTP through this anti-corruption-layer client.</p>
     *
     * <p>The response body is intentionally read as a {@code String} and discarded so that no
     * marketplace {@code Product} type crosses the service boundary (DTO-per-boundary rule,
     * §0.7.2). Error mapping mirrors {@link #getUnitPrice} / {@link #getQuote}: a downstream 404
     * becomes {@link InventoryNotFoundException} (HTTP 404); any other 4xx, any 5xx, and
     * connect/read timeouts become {@link ServiceUnavailableException} (HTTP 503), so orders-service
     * never leaks a raw 500 from this probe.</p>
     */
    public void verifyProductExists(Long productId) {
        String url = marketplaceBaseUrl + "/api/products/{productId}";
        try {
            // 200 body (the product JSON) is discarded; a 404 throws HttpClientErrorException.NotFound.
            restTemplate.getForObject(url, String.class, productId);
        } catch (HttpClientErrorException.NotFound e) {
            throw new InventoryNotFoundException("No such product: productId=" + productId, e);
        } catch (HttpClientErrorException e) {
            // Non-404 4xx (e.g. 401/403 on shared-token misconfiguration): fail fast as 503 rather
            // than leaking a raw 500 (consistent with the other client methods).
            throw new ServiceUnavailableException(
                    "marketplace-service returned unexpected " + e.getStatusCode()
                            + " while verifying productId=" + productId, e);
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "marketplace-service unavailable while verifying productId=" + productId, e);
        } catch (ResourceAccessException e) {
            // Connect/read timeout (bounded by RestTemplateConfig) or other I/O failure.
            throw new ServiceUnavailableException(
                    "marketplace-service unreachable/timed out while verifying productId=" + productId, e);
        }
    }
}
