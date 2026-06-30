package org.jboss.as.quickstarts.kitchensink.orders.client;

import java.math.BigDecimal;

/**
 * Local consumer DTO for the marketplace /quote response
 * ({"vendorId":..,"unitPrice":..,"weightLbs":..}). Resolves GAP-1 (best-vendor
 * selection lives in marketplace) and GAP-2 (product weight for shipping) in a single
 * round-trip. Distinct from marketplace's producer ProductQuoteResponse — no producer
 * type crosses the bounded-context boundary (orders consumes the quote over HTTP only).
 */
public record ProductQuoteDto(Long vendorId, BigDecimal unitPrice, BigDecimal weightLbs) {
}
