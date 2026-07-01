package org.jboss.as.quickstarts.kitchensink.marketplace.rest;

import java.math.BigDecimal;
import java.util.List;

import org.jboss.as.quickstarts.kitchensink.marketplace.dto.BestVendorResponse;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.Product;
import org.jboss.as.quickstarts.kitchensink.marketplace.repository.ProductRepository;
import org.jboss.as.quickstarts.kitchensink.marketplace.service.PricingService;
import org.jboss.as.quickstarts.kitchensink.marketplace.service.VendorSelectionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST boundary for the marketplace catalog/pricing bounded context.
 *
 * <p>Converted from the monolith JAX-RS resource ({@code @Path("/products")} +
 * {@code @RequestScoped}) to a Spring MVC {@code @RestController} mapped at
 * {@code /api/products}; externally reachable under the service context-path as
 * {@code /marketplace/api/products}. The dropped {@code JaxRsActivator}
 * ({@code @ApplicationPath("/rest")}) is replaced by Spring's DispatcherServlet.</p>
 *
 * <p>PRODUCER-ONLY: this service implements Contract 1 (Pricing) and makes no outbound
 * cross-service calls. Pricing/vendor logic is delegated to {@link PricingService} and
 * {@link VendorSelectionService}; the catalog is read directly via {@link ProductRepository}.</p>
 */
@RestController
@RequestMapping("/api/products")
public class ProductResourceRESTService {

    private final ProductRepository productRepository;
    private final PricingService pricingService;
    private final VendorSelectionService vendorSelectionService;

    public ProductResourceRESTService(ProductRepository productRepository,
                                      PricingService pricingService,
                                      VendorSelectionService vendorSelectionService) {
        this.productRepository = productRepository;
        this.pricingService = pricingService;
        this.vendorSelectionService = vendorSelectionService;
    }

    /**
     * GET /api/products
     * Returns all products sorted by name.
     */
    @GetMapping
    public ResponseEntity<List<Product>> listProducts() {
        return ResponseEntity.ok(productRepository.findAllByOrderByNameAsc());
    }

    /**
     * GET /api/products/{id}
     * Returns a single product by ID, or 404 if not found.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable("id") Long id) {
        return productRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Product not found: " + id));
    }

    /**
     * GET /api/products/category/{category}
     * Returns all products in a given category.
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<List<Product>> getProductsByCategory(
            @PathVariable("category") String category) {
        return ResponseEntity.ok(productRepository.findByCategoryOrderByNameAsc(category));
    }

    /**
     * GET /api/products/{id}/vendors?qty=N
     * Returns a vendor list for a product at a given qty, ranked by the Source-A score
     * (best vendor first) so the top entry matches the order-time selection (review C1).
     * Returns 400 when {@code qty < 1} and 404 when the product does not exist.
     */
    @GetMapping("/{id}/vendors")
    public ResponseEntity<List<VendorSelectionService.VendorPriceResult>> getVendorsForProduct(
            @PathVariable("id") Long id,
            @RequestParam(name = "qty", defaultValue = "1") int qty) {
        requireValidQty(qty);
        if (productRepository.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id);
        }
        List<VendorSelectionService.VendorPriceResult> vendors =
                vendorSelectionService.getVendorPricesForProduct(id, qty);
        return ResponseEntity.ok(vendors);
    }

    /**
     * GET /api/products/{id}/best-vendor?qty=N
     *
     * <p>Authoritative Source-A best-vendor selection endpoint (review findings C1/C2/C3). Delegates
     * to {@link VendorSelectionService#selectBestVendor(Long, int)} — the MAXIMIZATION scoring path
     * (AAP &sect;0.6.1) — and returns {@link BestVendorResponse} ({@code {"vendorId": N}}). This is the
     * endpoint orders-service consumes for vendor selection; it deliberately does NOT infer "best"
     * from the price-sorted catalog listing.</p>
     *
     * <p>Returns 400 when {@code qty < 1}, and 404 when the product does not exist OR no vendor can
     * satisfy the request (no eligible/in-stock vendor), so the consumer never silently treats a
     * missing selection as a valid vendor.</p>
     */
    @GetMapping("/{id}/best-vendor")
    public ResponseEntity<BestVendorResponse> getBestVendorForProduct(
            @PathVariable("id") Long id,
            @RequestParam(name = "qty", defaultValue = "1") int qty) {
        requireValidQty(qty);
        if (productRepository.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id);
        }
        Long vendorId = vendorSelectionService.selectBestVendor(id, qty);
        if (vendorId == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No eligible vendor for product " + id + " at qty " + qty);
        }
        return ResponseEntity.ok(new BestVendorResponse(vendorId));
    }

    /**
     * GET /api/products/{id}/price?vendorId=N&qty=N
     * Contract 1 (Pricing): returns the calculated unit price as a BARE BigDecimal JSON number.
     *
     * <p>Both {@code vendorId} and {@code qty} are <strong>required</strong> (QA Issue 1 / Contract 1
     * {@code quantity}&rarr;{@code qty} rename, AAP &sect;0.6.2/A4). {@code qty} intentionally has NO
     * default: a request that omits {@code qty} — for example one that still sends the obsolete
     * {@code quantity} parameter — is rejected with HTTP 400 rather than silently defaulting to
     * {@code qty=1} (which previously mispriced legacy callers at the qty-1 unit price). This makes
     * the contract rename observable at the boundary instead of masking it.</p>
     *
     * <p>Returns 400 when {@code vendorId} or {@code qty} is absent, or when {@code qty < 1}; 404 when
     * no vendor inventory exists for the (product, vendor) pair (InventoryNotFoundException, mapped
     * via its own @ResponseStatus).</p>
     */
    @GetMapping("/{id}/price")
    public ResponseEntity<BigDecimal> getPrice(
            @PathVariable("id") Long id,
            @RequestParam(name = "vendorId", required = false) Long vendorId,
            @RequestParam(name = "qty", required = false) Integer qty) {
        if (vendorId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "vendorId query parameter is required");
        }
        if (qty == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "qty query parameter is required");
        }
        requireValidQty(qty);
        BigDecimal unitPrice = pricingService.calculatePrice(id, vendorId, qty);
        return ResponseEntity.ok(unitPrice);
    }

    /**
     * Validates the {@code qty} query parameter for the pricing/vendor endpoints (review MED1).
     * Quantity drives the volume-discount tier and vendor eligibility, so a zero or negative value
     * is rejected with HTTP 400 rather than silently altering pricing behavior.
     *
     * @param qty the requested quantity
     * @throws ResponseStatusException 400 BAD_REQUEST when {@code qty < 1}
     */
    private static void requireValidQty(int qty) {
        if (qty < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "qty must be greater than or equal to 1");
        }
    }
}
