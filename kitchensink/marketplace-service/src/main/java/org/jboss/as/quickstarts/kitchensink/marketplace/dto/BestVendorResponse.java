package org.jboss.as.quickstarts.kitchensink.marketplace.dto;

/**
 * Producer DTO for the Source-A best-vendor selection endpoint
 * ({@code GET /api/products/{id}/best-vendor?qty=N}).
 *
 * <p>Carries the single best vendor id chosen by
 * {@link org.jboss.as.quickstarts.kitchensink.marketplace.service.VendorSelectionService#selectBestVendor(Long, int)}
 * using the Source-A MAXIMIZATION score (AAP &sect;0.6.1). This is the authoritative selection path
 * consumed by orders-service; it intentionally does NOT infer "best" from a price-sorted catalog
 * listing (review findings C1/C2/C3).</p>
 *
 * <p>Serialized as a JSON object {@code {"vendorId": 2}}. The boundary rule (AAP &sect;0.7.2) forbids
 * sharing this type across modules; orders-service declares its own consumer DTO, so the duplication
 * is intentional.</p>
 *
 * @param vendorId the id of the Source-A best vendor for the requested product/quantity
 */
public record BestVendorResponse(Long vendorId) {
}
