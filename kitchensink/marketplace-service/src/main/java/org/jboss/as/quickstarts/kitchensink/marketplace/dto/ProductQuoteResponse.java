package org.jboss.as.quickstarts.kitchensink.marketplace.dto;

import java.math.BigDecimal;

/**
 * Producer DTO for {@code GET /api/products/{id}/quote?qty=} on marketplace-service.
 *
 * <p>Resolves GAP-1 (best-vendor selection) and GAP-2 (product weight) in a single
 * round-trip: the orders-service quote consumer reads {@code vendorId},
 * {@code unitPrice}, and {@code weightLbs} together instead of issuing two calls.</p>
 *
 * <p>The component values originate from the marketplace domain:</p>
 * <ul>
 *   <li>{@code vendorId}  - the chosen vendor from {@code VendorSelectionService.selectBestVendor(...)}.</li>
 *   <li>{@code unitPrice} - the computed price from {@code PricingService.calculatePrice(...)} (4 decimal places).</li>
 *   <li>{@code weightLbs} - the product weight from {@code Product.getWeightLbs()} (NUMERIC(8,4), e.g. {@code 0.5500}).</li>
 * </ul>
 *
 * <p>Serializes via Jackson (default in spring-boot-starter-web) to
 * {@code {"vendorId":...,"unitPrice":...,"weightLbs":...}} using the record component
 * names as JSON keys; no custom serializer is required.</p>
 *
 * <p>This producer type is NOT shared across the service boundary - the orders-service
 * quote consumer declares its own separate local DTO (DTO-per-boundary).</p>
 */
public record ProductQuoteResponse(Long vendorId, BigDecimal unitPrice, BigDecimal weightLbs) {
}
