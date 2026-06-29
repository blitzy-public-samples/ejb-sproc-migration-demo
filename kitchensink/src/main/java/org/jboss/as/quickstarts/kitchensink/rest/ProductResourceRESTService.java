package org.jboss.as.quickstarts.kitchensink.rest;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.jboss.as.quickstarts.kitchensink.data.ProductRepository;
import org.jboss.as.quickstarts.kitchensink.model.Product;
import org.jboss.as.quickstarts.kitchensink.service.PricingService;
import org.jboss.as.quickstarts.kitchensink.service.VendorSelectionService;

/**
 * Product catalog REST endpoints.
 *
 * <p>Migrated from JAX-RS ({@code @Path}) to Spring MVC ({@code @RestController}). The class-level
 * mapping is {@code /api/products}; combined with the application context path
 * ({@code server.servlet.context-path=/kitchensink}) the externally visible base is
 * {@code /kitchensink/api/products}, exactly matching the URLs the PHP storefront is hardcoded to
 * (this mapping must NOT be prefixed with {@code /kitchensink} — the context path supplies it). The
 * resource now uses {@link ResponseEntity} return types, Spring MVC path/request-parameter binding,
 * and constructor injection.</p>
 *
 * <p>The Spring Data {@code findById} returns {@code Optional<Product>}, so the not-found paths use
 * {@code Optional} idioms. All endpoint paths, JSON shapes, and HTTP status codes are preserved. The
 * price endpoint is now exception-driven: a missing product/vendor/inventory combination causes the
 * pricing service to raise a not-found exception that the global {@code RestExceptionHandler} maps
 * to 404.</p>
 */
@RestController
@RequestMapping("/api/products")
public class ProductResourceRESTService {

    private final ProductRepository productRepository;
    private final PricingService pricingService;
    private final VendorSelectionService vendorSelectionService;

    // Single constructor -> Spring performs constructor injection automatically (no @Autowired
    // needed). Replaces the former CDI field injection of the repository and the two services.
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
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * GET /api/products/category/{category}
     * Returns all products in a given category, sorted by name.
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<List<Product>> getProductsByCategory(@PathVariable("category") String category) {
        return ResponseEntity.ok(productRepository.findByCategoryOrderByNameAsc(category));
    }

    /**
     * GET /api/products/{id}/vendors?quantity=N
     * Returns the ranked vendor list for a product at a given quantity, or 404 if the product is
     * absent. {@code quantity} defaults to 1.
     */
    @GetMapping("/{id}/vendors")
    public ResponseEntity<?> getVendorsForProduct(
            @PathVariable("id") Long id,
            @RequestParam(value = "quantity", defaultValue = "1") int quantity) {
        if (productRepository.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<VendorSelectionService.VendorPriceResult> vendors =
            vendorSelectionService.getVendorPricesForProduct(id, quantity);
        return ResponseEntity.ok(vendors);
    }

    /**
     * GET /api/products/{id}/price?vendorId=N&amp;quantity=N
     * Returns the calculated unit price for a product/vendor/quantity combination.
     *
     * <p>{@code vendorId} is required: a missing value triggers Spring's automatic 400 Bad Request.
     * A missing product/vendor/inventory combination makes {@link PricingService#calculatePrice}
     * raise a not-found exception that the global {@code RestExceptionHandler} maps to 404 — so this
     * method neither validates existence inline nor catches the exception. {@code quantity} defaults
     * to 1. The raw {@link BigDecimal} unit price is returned (serialized as a JSON number),
     * preserving the legacy response body exactly.</p>
     */
    @GetMapping("/{id}/price")
    public ResponseEntity<BigDecimal> getPrice(
            @PathVariable("id") Long id,
            @RequestParam("vendorId") Long vendorId,
            @RequestParam(value = "quantity", defaultValue = "1") int quantity) {
        BigDecimal unitPrice = pricingService.calculatePrice(id, vendorId, quantity);
        return ResponseEntity.ok(unitPrice);
    }
}
