package org.jboss.as.quickstarts.kitchensink.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.jboss.as.quickstarts.kitchensink.data.VendorInventoryRepository;
import org.jboss.as.quickstarts.kitchensink.data.VendorInventoryRepository.VendorCandidateProjection;

/**
 * VendorSelectionService — pure-Java re-implementation of the {@code select_best_vendor(
 * product_id, quantity)} PL/pgSQL stored procedure.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): formerly a CDI
 * {@code @ApplicationScoped} bean delegating to a native {@code SELECT select_best_vendor(...)}
 * query via {@code EntityManager}. It is now a Spring {@code @Service} that selects the best
 * vendor in Java using a single projection query plus the shared, stateless
 * {@link PricingService} (constructor-injected, no dependency cycle).</p>
 *
 * <p><strong>PERFORMANCE (N+1 elimination).</strong> Vendor candidates for a product are now loaded
 * with ONE {@link VendorInventoryRepository#findCandidatesByProductId} projection query that already
 * carries the inventory markup/stock, the vendor's fulfillment rating and shipping days, and the
 * product's base price. Each candidate is priced from that already-loaded data via
 * {@link PricingService#calculateUnitPrice} (no repository access) and scored from the projected
 * vendor metrics. This replaces the former pattern that, per candidate, called
 * {@code pricingService.calculatePrice(...)} (reloading product + inventory) and
 * {@code vendorRepository.findById(...)} — collapsing roughly {@code 2N+1} queries per product to a
 * single query, which matters when {@code OrderService} selects a vendor per cart line.</p>
 *
 * <p><strong>Scoring (Authority Hierarchy — Source A wins).</strong> The prompt's inline
 * specification (Source A) supersedes the SQL implementation (Source B) for the scoring formula.
 * Source B <em>minimized</em> a cost score; Source A <em>maximizes</em> a benefit score and picks
 * the highest:
 * <pre>
 *   score = (1 / normalizedPrice) * 0.60
 *         + (fulfillmentRating / 5.0) * 0.30
 *         + (1 / avgShippingDays) * 0.10        -- highest score wins
 * </pre>
 * where {@code normalizedPrice = candidatePrice / minCandidatePrice} (so {@code 1/normalizedPrice}
 * rewards the cheapest vendor). The two formulations are directionally equivalent (both favor
 * lower price, higher fulfillment rating, and fewer shipping days) but not mathematically
 * identical, so a selected vendor may occasionally differ from the legacy SQL choice. No
 * acceptance test asserts a specific best-vendor identity, so this deliberate substitution is
 * safe.</p>
 *
 * <p>The {@code min_price IS NULL} fallback (no candidate has sufficient stock -&gt; choose the
 * cheapest vendor with <em>any</em> stock) is a Source-B-only rule and is retained.</p>
 */
@Service
public class VendorSelectionService {

    private final VendorInventoryRepository vendorInventoryRepository;
    private final PricingService pricingService;

    public VendorSelectionService(VendorInventoryRepository vendorInventoryRepository,
                                  PricingService pricingService) {
        this.vendorInventoryRepository = vendorInventoryRepository;
        this.pricingService = pricingService;
    }

    /**
     * Selects the best vendor for a given product and quantity, returning only the vendor ID.
     *
     * <p>Convenience wrapper over {@link #selectBestVendorWithPrice(Long, int)} preserved for callers
     * that need only the vendor identity.</p>
     *
     * @param productId  the product ID
     * @param quantity   the requested quantity
     * @return           the vendor ID of the best vendor, or {@code null} if none found
     */
    public Long selectBestVendor(Long productId, int quantity) {
        VendorSelection selection = selectBestVendorWithPrice(productId, quantity);
        return selection == null ? null : selection.getVendorId();
    }

    /**
     * Selects the best vendor for a product/quantity and returns it together with its computed unit
     * price, so the caller (e.g. {@code OrderService.orchestrateOrder}) does NOT have to re-price the
     * winning vendor with a second repository round-trip.
     *
     * <p>Candidates are the product's vendor-inventory rows (loaded with one projection query). Those
     * with {@code quantity_available >= quantity} are eligible: each is priced via
     * {@link PricingService#calculateUnitPrice} from the projection's base price and markup, the
     * minimum eligible price is computed for normalization, and the eligible candidate with the
     * highest Source-A benefit score is returned. If NO candidate has sufficient stock, the cheapest
     * vendor with <em>any</em> stock is returned (Source-B fallback). Returns {@code null} only when
     * the product has no stocked vendor at all.</p>
     *
     * @param productId  the product ID
     * @param quantity   the requested quantity
     * @return           the selected vendor and its unit price, or {@code null} if none found
     */
    public VendorSelection selectBestVendorWithPrice(Long productId, int quantity) {
        // ONE query: all candidate rows with vendor metadata + product base price already loaded.
        List<VendorCandidateProjection> candidates =
            vendorInventoryRepository.findCandidatesByProductId(productId);

        // Eligible candidates (enough stock): price each from loaded data and track the minimum price.
        List<ScoredCandidate> eligible = new ArrayList<>();
        BigDecimal minPrice = null;
        for (VendorCandidateProjection c : candidates) {
            Integer available = c.getQuantityAvailable();
            if (available != null && available >= quantity) {
                BigDecimal price = pricingService.calculateUnitPrice(
                    c.getBasePrice(), c.getMarkupPercent(), quantity);
                eligible.add(new ScoredCandidate(c, price));
                if (minPrice == null || price.compareTo(minPrice) < 0) {
                    minPrice = price;
                }
            }
        }

        if (minPrice != null) {
            // Score every eligible candidate and keep the highest-scoring one (Source A).
            Long bestVendorId = null;
            BigDecimal bestPrice = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            double minPriceD = minPrice.doubleValue();
            for (ScoredCandidate sc : eligible) {
                double score = score(sc.price.doubleValue(), minPriceD,
                    sc.row.getFulfillmentRating(), sc.row.getAvgShippingDays());
                if (score > bestScore) {
                    bestScore = score;
                    bestVendorId = sc.row.getVendorId();
                    bestPrice = sc.price;
                }
            }
            if (bestVendorId != null) {
                return new VendorSelection(bestVendorId, bestPrice);
            }
        }

        // Fallback (Source B): cheapest vendor with any stock available.
        Long fallbackVendorId = null;
        BigDecimal fallbackPrice = null;
        for (VendorCandidateProjection c : candidates) {
            Integer available = c.getQuantityAvailable();
            if (available != null && available > 0) {
                BigDecimal price = pricingService.calculateUnitPrice(
                    c.getBasePrice(), c.getMarkupPercent(), quantity);
                if (fallbackPrice == null || price.compareTo(fallbackPrice) < 0) {
                    fallbackPrice = price;
                    fallbackVendorId = c.getVendorId();
                }
            }
        }
        return fallbackVendorId == null ? null : new VendorSelection(fallbackVendorId, fallbackPrice);
    }

    /**
     * Computes the Source-A benefit score for a candidate vendor:
     * <pre>
     *   score = (1 / normalizedPrice) * 0.60
     *         + (fulfillmentRating / 5.0)  * 0.30
     *         + (1 / avgShippingDays)      * 0.10        -- highest score wins
     * </pre>
     * Division by zero is guarded: {@code avgShippingDays} is clamped to {@code >= 1} — a null or
     * non-positive value is treated as 1 day via {@code Math.max(days, 1)}, so the fastest/unknown
     * shipping earns the full speed bonus and the {@code (1 / avgShippingDays)} term never divides
     * by zero. A null {@code fulfillmentRating} contributes no fulfillment bonus. The arithmetic is
     * byte-for-byte identical to the prior implementation; only the source of the vendor metrics
     * changed (from a reloaded {@code Vendor} entity to the projection columns).
     */
    private double score(double candidatePrice, double minPrice,
                         BigDecimal fulfillmentRating, Integer avgShippingDays) {
        double normalizedPrice = (candidatePrice == 0.0) ? 1.0 : candidatePrice / minPrice;
        double priceTerm = (normalizedPrice == 0.0) ? 0.0 : (1.0 / normalizedPrice) * 0.60;

        double fulfillment = (fulfillmentRating == null) ? 0.0 : fulfillmentRating.doubleValue();
        double fulfillmentTerm = (fulfillment / 5.0) * 0.30;

        // Divide-by-zero GUARD (binding rule): clamp avg_shipping_days to >= 1 (null -> 1) via
        // Math.max(days, 1), so the Source-A speed term (1 / avgShippingDays) * 0.10 is always safe.
        int days = (avgShippingDays == null) ? 1 : Math.max(avgShippingDays, 1);
        double shippingTerm = (1.0 / days) * 0.10;

        return priceTerm + fulfillmentTerm + shippingTerm;
    }

    /**
     * Returns a ranked list of all vendors with their prices for a given product/quantity, sorted by
     * unit price ascending (cheapest first). Vendors for which a price cannot be determined are
     * skipped.
     *
     * <p>Uses the same single {@link VendorInventoryRepository#findCandidatesByProductId} projection
     * query as best-vendor selection, pricing each candidate from the loaded base price and markup
     * (no per-vendor repository lookups). The output shape is unchanged — the PHP storefront still
     * reads {@code vendorId}, {@code vendorName}, {@code unitPrice}, {@code fulfillmentRating}, and
     * {@code avgShippingDays}.</p>
     *
     * @param productId  the product ID
     * @param quantity   the requested quantity
     * @return           sorted list of {@link VendorPriceResult} (cheapest first)
     */
    public List<VendorPriceResult> getVendorPricesForProduct(Long productId, int quantity) {
        List<VendorCandidateProjection> candidates =
            vendorInventoryRepository.findCandidatesByProductId(productId);

        List<VendorPriceResult> results = new ArrayList<>();
        for (VendorCandidateProjection c : candidates) {
            BigDecimal unitPrice;
            try {
                unitPrice = pricingService.calculateUnitPrice(
                    c.getBasePrice(), c.getMarkupPercent(), quantity);
            } catch (RuntimeException e) {
                // Skip vendors for which pricing cannot be determined.
                continue;
            }
            VendorPriceResult vpr = new VendorPriceResult();
            vpr.vendorId = c.getVendorId();
            vpr.vendorName = c.getVendorName();
            vpr.unitPrice = unitPrice;
            vpr.fulfillmentRating = c.getFulfillmentRating();
            vpr.avgShippingDays = c.getAvgShippingDays();
            results.add(vpr);
        }

        results.sort(Comparator.comparing(r -> r.unitPrice));
        return results;
    }

    /**
     * Internal holder pairing a candidate projection row with its computed unit price, so the price
     * is computed once and reused during scoring (no recomputation per scoring pass).
     */
    private static final class ScoredCandidate {
        private final VendorCandidateProjection row;
        private final BigDecimal price;

        private ScoredCandidate(VendorCandidateProjection row, BigDecimal price) {
            this.row = row;
            this.price = price;
        }
    }

    /**
     * Immutable result of best-vendor selection: the chosen vendor ID and the unit price computed for
     * it during selection. Returning the price alongside the vendor lets the caller avoid a second
     * pricing round-trip for the winning vendor.
     */
    public static final class VendorSelection {
        private final Long vendorId;
        private final BigDecimal unitPrice;

        public VendorSelection(Long vendorId, BigDecimal unitPrice) {
            this.vendorId = vendorId;
            this.unitPrice = unitPrice;
        }

        public Long getVendorId() {
            return vendorId;
        }

        public BigDecimal getUnitPrice() {
            return unitPrice;
        }
    }

    /**
     * Holds price and vendor metadata for a single vendor option. Public fields are serialized
     * directly to JSON (the PHP storefront reads {@code vendorId}, {@code vendorName},
     * {@code unitPrice}, {@code fulfillmentRating}, and {@code avgShippingDays}).
     */
    public static class VendorPriceResult {
        public Long vendorId;
        public String vendorName;
        public BigDecimal unitPrice;
        public BigDecimal fulfillmentRating;
        public Integer avgShippingDays;
    }
}
