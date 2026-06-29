package org.jboss.as.quickstarts.kitchensink.rest;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
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
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): converted from a JAX-RS
 * {@code @Path("/products") @RequestScoped} resource to a Spring MVC {@code @RestController}.
 * The class-level base path is {@code /api/products}; combined with the application context path
 * ({@code /kitchensink}) the externally visible base is {@code /kitchensink/api/products}, exactly
 * matching the URLs the PHP storefront is hardcoded to. JAX-RS {@code Response} is replaced by
 * {@link ResponseEntity}; {@code @PathParam}/{@code @QueryParam} by {@code @PathVariable}/
 * {@code @RequestParam}; field {@code @Inject} by constructor injection.</p>
 *
 * <p>The {@code findById} return type changed from entity-or-null (legacy custom repository) to
 * {@code Optional<Product>} (Spring Data); callers adapt with {@code orElse(null)}. All paths,
 * JSON shapes, and HTTP status codes are preserved.</p>
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
     * GET /api/products — returns all products.
     */
    @GetMapping
    public ResponseEntity<List<Product>> listProducts() {
        return ResponseEntity.ok(productRepository.findAll());
    }

    /**
     * GET /api/products/{id} — returns a single product by ID, or 404 if not found.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getProduct(@PathVariable("id") Long id) {
        Product product = productRepository.findById(id).orElse(null);
        if (product == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Product not found: " + id);
        }
        return ResponseEntity.ok(product);
    }

    /**
     * GET /api/products/category/{category} — returns all products in a given category.
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<List<Product>> getProductsByCategory(@PathVariable("category") String category) {
        return ResponseEntity.ok(productRepository.findByCategoryOrderByNameAsc(category));
    }

    /**
     * GET /api/products/{id}/vendors?quantity=N — returns the ranked vendor list for a product at a
     * given quantity. Defaults quantity to 1.
     */
    @GetMapping("/{id}/vendors")
    public ResponseEntity<?> getVendorsForProduct(
            @PathVariable("id") Long id,
            @RequestParam(value = "quantity", defaultValue = "1") int quantity) {
        Product product = productRepository.findById(id).orElse(null);
        if (product == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Product not found: " + id);
        }
        List<VendorSelectionService.VendorPriceResult> vendors =
            vendorSelectionService.getVendorPricesForProduct(id, quantity);
        return ResponseEntity.ok(vendors);
    }

    /**
     * GET /api/products/{id}/price?vendorId=N&quantity=N — returns the calculated unit price for a
     * product/vendor/quantity combination. Returns 400 if {@code vendorId} is absent or the price
     * cannot be computed (e.g., the vendor does not stock the product), and 404 if the product does
     * not exist — preserving the legacy endpoint's exact status contract.
     */
    @GetMapping("/{id}/price")
    public ResponseEntity<?> getPrice(
            @PathVariable("id") Long id,
            @RequestParam(value = "vendorId", required = false) Long vendorId,
            @RequestParam(value = "quantity", defaultValue = "1") int quantity) {
        if (vendorId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("vendorId query parameter is required");
        }
        Product product = productRepository.findById(id).orElse(null);
        if (product == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Product not found: " + id);
        }
        try {
            BigDecimal unitPrice = pricingService.calculatePrice(id, vendorId, quantity);
            return ResponseEntity.ok(unitPrice);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Could not calculate price: " + e.getMessage());
        }
    }
}
