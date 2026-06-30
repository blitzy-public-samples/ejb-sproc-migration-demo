package org.jboss.as.quickstarts.kitchensink.orders.client;

import java.math.BigDecimal;

/**
 * Cross-domain HTTP gateway from orders-service to marketplace-service. One of the two
 * legal cross-domain channels out of orders-service (anti-corruption layer / gateway
 * pattern — AAP &sect;0.3.3, &sect;0.7.2); the other is {@link UsersClient}.
 *
 * <p>Externalizes the in-process calls the monolith's {@code OrderService} previously made
 * to {@code VendorSelectionService.selectBestVendor} and {@code PricingService.calculatePrice}
 * (both now live in marketplace-service and MUST NOT be imported). All communication is over
 * HTTP+JSON.</p>
 *
 * <p>Declared as an interface so integration tests (OrderServiceIT) can stub it (e.g. with
 * {@code @MockBean}) while {@code MarketplaceClientImpl} is exercised against
 * {@code MockRestServiceServer}/WireMock.</p>
 *
 * <p>Implements Contract 1 (Pricing) plus the vendor-ranking/selection read (AAP &sect;0.6.2,
 * &sect;0.6.4). Note (A3): any UI-supplied {@code vendorId} is transient; the vendor is chosen
 * here at order time via {@link #selectBestVendor(Long, int)}, never read from the cart.</p>
 */
public interface MarketplaceClient {

    /**
     * Selects the best vendor for a product at a given quantity by reading the marketplace
     * AUTHORITATIVE best-vendor endpoint
     * ({@code GET {marketplace.base-url}/api/products/{productId}/best-vendor?qty=}) and returning
     * the chosen vendor's id.
     *
     * <p>The selection (the migrated {@code select_best_vendor} Source-A MAXIMIZATION logic, AAP
     * &sect;0.6.1) is owned by marketplace-service; this thin gateway does NOT re-rank. It consumes the
     * dedicated {@code /best-vendor} endpoint — which returns the single Source-A-maximized vendor as
     * {@code {"vendorId": N}} — rather than inferring "best" from the price-sorted catalog listing
     * (review findings C1/C2/C3): consuming the catalog list would have made orders-service silently
     * pick the cheapest vendor instead of the Source-A best one.</p>
     *
     * @param productId the product identifier
     * @param qty       the requested quantity (affects vendor eligibility / volume tier)
     * @return the Source-A best vendor's id, or {@code null} when marketplace reports no eligible
     *         vendor for the product/quantity (HTTP 404). Callers MUST treat {@code null} as "no
     *         eligible vendor" and abort the line rather than silently skipping it (review M1).
     * @throws org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException
     *         if marketplace-service responds with a 5xx status or is unreachable
     */
    Long selectBestVendor(Long productId, int qty);

    /**
     * Returns the catalog weight (in pounds) of a product by reading the marketplace product
     * endpoint ({@code GET {marketplace.base-url}/api/products/{productId}}) and projecting its
     * {@code weightLbs} field.
     *
     * <p><strong>Why this exists (review finding C5 — shipping parity).</strong> The monolith's
     * {@code process_order} accumulated {@code COALESCE(weight_lbs, 0) * quantity} per cart line by
     * joining the {@code products} table, and shipping is computed from that total weight. orders-service
     * owns no {@code Product} entity (boundary rule, AAP &sect;0.7.2), so it reads the weight over HTTP
     * here. The catalog row already exposes {@code weightLbs}, so no new producer endpoint is needed.</p>
     *
     * @param productId the product identifier
     * @return the product's weight in pounds; {@link java.math.BigDecimal#ZERO} when marketplace
     *         returns 404 or a null weight (faithful to the procedure's {@code COALESCE(weight_lbs, 0)})
     * @throws org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException
     *         if marketplace-service responds with a 5xx status or is unreachable
     */
    BigDecimal getProductWeight(Long productId);

    /**
     * Returns the unit price for a (product, vendor, quantity) combination per Contract 1
     * (Pricing): {@code GET {marketplace.base-url}/api/products/{productId}/price?vendorId=&qty=}
     * &rarr; 200 with a bare {@code BigDecimal} JSON number (no wrapper).
     *
     * @param productId the product identifier
     * @param vendorId  the chosen vendor identifier
     * @param qty       the order quantity (drives the volume-discount tier)
     * @return the unit price as a {@code BigDecimal}
     * @throws org.jboss.as.quickstarts.kitchensink.orders.exception.InventoryNotFoundException
     *         if marketplace returns 404 (no vendor_inventory for the product/vendor pair)
     * @throws org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException
     *         if marketplace-service responds with a 5xx status or is unreachable
     */
    BigDecimal getPrice(Long productId, Long vendorId, int qty);
}
