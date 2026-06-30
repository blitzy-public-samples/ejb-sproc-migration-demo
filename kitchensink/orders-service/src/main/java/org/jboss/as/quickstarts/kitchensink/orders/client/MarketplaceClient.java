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
     * vendor-ranking endpoint
     * ({@code GET {marketplace.base-url}/api/products/{productId}/vendors?qty=}) and returning
     * the top-ranked vendor's id.
     *
     * <p>The ranking/scoring (the migrated {@code select_best_vendor} Source A logic, AAP
     * &sect;0.6.1) is owned by marketplace-service; this thin gateway does NOT re-rank — it
     * trusts the order returned by marketplace and takes the first entry.</p>
     *
     * @param productId the product identifier
     * @param qty       the requested quantity (affects vendor eligibility / volume tier)
     * @return the best vendor's id, or {@code null} when marketplace reports no available
     *         vendor for the product (faithful to the monolith's {@code selectBestVendor}
     *         returning {@code null}, which makes the caller skip the line item)
     * @throws org.jboss.as.quickstarts.kitchensink.orders.exception.ServiceUnavailableException
     *         if marketplace-service responds with a 5xx status or is unreachable
     */
    Long selectBestVendor(Long productId, int qty);

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
