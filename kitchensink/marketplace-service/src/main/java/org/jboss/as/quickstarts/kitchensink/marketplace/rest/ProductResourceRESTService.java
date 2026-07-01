package org.jboss.as.quickstarts.kitchensink.marketplace.rest;

import java.math.BigDecimal;
import java.util.List;

import org.jboss.as.quickstarts.kitchensink.marketplace.dto.ProductQuoteResponse;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.Product;
import org.jboss.as.quickstarts.kitchensink.marketplace.repository.ProductRepository;
import org.jboss.as.quickstarts.kitchensink.marketplace.service.PricingService;
import org.jboss.as.quickstarts.kitchensink.marketplace.service.VendorSelectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST edge for the marketplace-service product catalog. Spring MVC replacement for the
 * legacy JAX-RS ProductResourceRESTService. All legacy paths are preserved under the base
 * mapping /api/products (combined with context-path /marketplace). Also serves the Contract 1
 * pricing producer (/price) and the new /quote endpoint that resolves GAP-1 + GAP-2.
 */
@RestController
@RequestMapping("/api/products")
public class ProductResourceRESTService {

    private final ProductRepository productRepository;
    private final PricingService pricingService;
    private final VendorSelectionService vendorSelectionService;

    // Constructor injection (single constructor -> no @Autowired required).
    public ProductResourceRESTService(ProductRepository productRepository,
                                      PricingService pricingService,
                                      VendorSelectionService vendorSelectionService) {
        this.productRepository = productRepository;
        this.pricingService = pricingService;
        this.vendorSelectionService = vendorSelectionService;
    }

    /** GET /api/products - all products sorted by name. */
    @GetMapping
    public List<Product> listProducts() {
        return productRepository.findAllByOrderByNameAsc();
    }

    /** GET /api/products/{id} - one product, 404 if absent (legacy semantics preserved). */
    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable Long id) {
        return productRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** GET /api/products/category/{category} - products in a category, sorted by name. */
    @GetMapping("/category/{category}")
    public List<Product> getProductsByCategory(@PathVariable String category) {
        return productRepository.findByCategoryOrderByNameAsc(category);
    }

    /**
     * GET /api/products/{id}/vendors?qty=N (also accepts the legacy alias ?quantity=N) -
     * ranked vendor list; 404 if product absent.
     *
     * <p>QA F9 Issue #2 continuity fix: this {@code /vendors} endpoint is a storefront-only path.
     * The frozen PHP storefront ({@code frontend/product.php}) calls it with the pre-migration
     * query-parameter name {@code quantity}. The AAP {@code quantity}->{@code qty} rename
     * (§0.6.7) is scoped to the Contract&nbsp;1 {@code /price} producer only; per §0.7.1/§0.3.4
     * the storefront's observable behavior must be preserved, so BOTH parameter names are
     * accepted here. {@code qty} takes precedence when both are supplied; when neither is
     * supplied the quantity defaults to 1 (matching the legacy {@code @DefaultValue("1")}).
     */
    @GetMapping("/{id}/vendors")
    public ResponseEntity<List<VendorSelectionService.VendorPriceResult>> getVendorsForProduct(
            @PathVariable Long id,
            @RequestParam(name = "qty", required = false) Integer qty,
            @RequestParam(name = "quantity", required = false) Integer quantity) {
        if (productRepository.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Coalesce the canonical `qty` with the legacy `quantity` alias (qty wins), default 1.
        int effectiveQty = (qty != null) ? qty : (quantity != null ? quantity : 1);
        return ResponseEntity.ok(vendorSelectionService.getVendorPricesForProduct(id, effectiveQty));
    }

    /**
     * GET /api/products/{productId}/price?vendorId=N&qty=N (Contract 1 producer).
     * Returns a bare BigDecimal (JSON number). Missing inventory propagates
     * InventoryNotFoundException -> HTTP 404 (NOT the legacy 400).
     */
    @GetMapping("/{productId}/price")
    public BigDecimal getPrice(
            @PathVariable Long productId,
            @RequestParam Long vendorId,
            @RequestParam(name = "qty", defaultValue = "1") int qty) {
        return pricingService.calculatePrice(productId, vendorId, qty);
    }

    /**
     * GET /api/products/{productId}/quote?qty=N (NEW; resolves GAP-1 best-vendor + GAP-2 weight).
     * Returns {vendorId, unitPrice, weightLbs} in one round-trip.
     */
    @GetMapping("/{productId}/quote")
    public ResponseEntity<ProductQuoteResponse> getQuote(
            @PathVariable Long productId,
            @RequestParam(name = "qty", defaultValue = "1") int qty) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        Long vendorId = vendorSelectionService.selectBestVendor(productId, qty);
        if (vendorId == null) {
            return ResponseEntity.notFound().build();
        }
        BigDecimal unitPrice = pricingService.calculatePrice(productId, vendorId, qty);
        BigDecimal weightLbs = product.getWeightLbs();
        return ResponseEntity.ok(new ProductQuoteResponse(vendorId, unitPrice, weightLbs));
    }
}
