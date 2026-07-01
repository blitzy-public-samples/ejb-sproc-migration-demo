package org.jboss.as.quickstarts.kitchensink.orders.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import org.jboss.as.quickstarts.kitchensink.orders.exception.InventoryNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.ProductNotFoundException;
import org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
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
            // Consume the AUTHORITATIVE Source-A best-vendor endpoint (review C1/C2/C3). It returns the
            // single Source-A-maximized vendor as {"vendorId": N}; we do NOT infer "best" from the
            // price-sorted catalog list (which would silently pick the cheapest vendor).
            BestVendorOption best = restClient.get()
                    .uri("/api/products/{productId}/best-vendor?qty={qty}", productId, qty)
                    .retrieve()
                    .body(BestVendorOption.class);
            if (best == null) {
                return null;
            }
            return best.vendorId();
        } catch (HttpClientErrorException.NotFound e) {
            // 404 -> no eligible vendor for this product/qty. Return null; the caller (OrderService)
            // converts a null selection into a NoEligibleVendorException rather than skipping the line.
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
    public BigDecimal getProductWeight(Long productId) {
        try {
            // Read the catalog row and project its weightLbs field (review C5). The catalog already
            // exposes weight, so no new marketplace producer endpoint is required.
            ProductWeightOption product = restClient.get()
                    .uri("/api/products/{productId}", productId)
                    .retrieve()
                    .body(ProductWeightOption.class);
            if (product == null || product.weightLbs() == null) {
                // Faithful to process_order's COALESCE(weight_lbs, 0): an absent/null weight is 0.
                return BigDecimal.ZERO;
            }
            return product.weightLbs();
        } catch (HttpClientErrorException.NotFound e) {
            // 404 -> product not found in the catalog -> treat its weight as 0 (COALESCE(weight_lbs, 0)).
            return BigDecimal.ZERO;
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "marketplace-service returned an error reading the weight of product " + productId);
        } catch (RestClientException e) {
            throw new ServiceUnavailableException(
                    "marketplace-service is unavailable reading the weight of product " + productId, e);
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

    @Override
    public void verifyProductExists(Long productId) {
        try {
            // Pure existence check (QA Issue 2): read the catalog row and discard the body. A 2xx means
            // the product exists and an order_draft_items FK to it will hold; a 404 means it does not,
            // which we surface as a controlled ProductNotFoundException (HTTP 404) BEFORE persisting a
            // draft row — instead of letting the DB raise a DataIntegrityViolationException (HTTP 500).
            restClient.get()
                    .uri("/api/products/{productId}", productId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductNotFoundException("Product not found: " + productId, e);
        } catch (HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "marketplace-service returned an error verifying product " + productId);
        } catch (RestClientException e) {
            throw new ServiceUnavailableException(
                    "marketplace-service is unavailable verifying product " + productId, e);
        }
    }

    /**
     * Minimal projection of the marketplace best-vendor response. The {@code /best-vendor} endpoint
     * returns the single Source-A-maximized vendor as {@code {"vendorId": N}}; orders-service needs
     * only that id. Declared as a private nested type to avoid creating a shared cross-service DTO
     * (boundary rule, AAP &sect;0.7.2); tolerant of unknown properties so an additive producer change
     * cannot break the read.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BestVendorOption(Long vendorId) {
    }

    /**
     * Minimal projection of the marketplace product (catalog) response. The {@code /api/products/{id}}
     * endpoint returns the full product object (id, name, sku, basePrice, weightLbs, category, ...);
     * orders-service needs only {@code weightLbs} to accumulate cart weight for shipping (review C5).
     * Declared as a private nested type to avoid a shared cross-service DTO (boundary rule); tolerant
     * of unknown properties so the rest of the catalog payload is ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProductWeightOption(BigDecimal weightLbs) {
    }
}
