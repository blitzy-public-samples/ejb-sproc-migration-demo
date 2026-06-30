package org.jboss.as.quickstarts.kitchensink.marketplace.rest;

import java.math.BigDecimal;
import java.util.List;

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
     * Returns a ranked vendor list for a product at a given qty.
     */
    @GetMapping("/{id}/vendors")
    public ResponseEntity<List<VendorSelectionService.VendorPriceResult>> getVendorsForProduct(
            @PathVariable("id") Long id,
            @RequestParam(name = "qty", defaultValue = "1") int qty) {
        if (productRepository.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id);
        }
        List<VendorSelectionService.VendorPriceResult> vendors =
                vendorSelectionService.getVendorPricesForProduct(id, qty);
        return ResponseEntity.ok(vendors);
    }

    /**
     * GET /api/products/{id}/price?vendorId=N&qty=N
     * Contract 1 (Pricing): returns the calculated unit price as a BARE BigDecimal JSON number.
     * Returns 400 when vendorId is absent; 404 when no vendor inventory exists for the
     * (product, vendor) pair (InventoryNotFoundException, mapped via its own @ResponseStatus).
     */
    @GetMapping("/{id}/price")
    public ResponseEntity<BigDecimal> getPrice(
            @PathVariable("id") Long id,
            @RequestParam(name = "vendorId", required = false) Long vendorId,
            @RequestParam(name = "qty", defaultValue = "1") int qty) {
        if (vendorId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "vendorId query parameter is required");
        }
        BigDecimal unitPrice = pricingService.calculatePrice(id, vendorId, qty);
        return ResponseEntity.ok(unitPrice);
    }
}
