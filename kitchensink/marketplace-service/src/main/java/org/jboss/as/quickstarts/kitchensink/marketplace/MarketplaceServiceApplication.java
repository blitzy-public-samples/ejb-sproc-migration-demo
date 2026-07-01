package org.jboss.as.quickstarts.kitchensink.marketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the marketplace-service (catalog/pricing bounded context).
 *
 * <p>Owns the {@code products}, {@code vendors}, and {@code vendor_inventory} tables in the
 * shared PostgreSQL database (logical ownership; {@code ddl-auto=validate}). Runs on port 8081
 * under context-path {@code /marketplace}.</p>
 *
 * <p>Cross-service role: PRODUCER ONLY — implements Contract 1 (Pricing):
 * {@code GET /api/products/{productId}/price?vendorId=&qty=}. It makes no outbound cross-service
 * HTTP calls, so there is no {@code client/} or {@code dto/} package and no RestClient/HTTP-client bean here.</p>
 *
 * <p>This class sits at the domain-root package so Spring Boot's default component scan reaches
 * the {@code model}, {@code repository}, {@code service}, {@code rest}, and {@code exception}
 * sub-packages. It deliberately does NOT declare {@code @EnableScheduling} — scheduled work
 * (nightly tier recalculation) lives only in users-service.</p>
 */
@SpringBootApplication
public class MarketplaceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketplaceServiceApplication.class, args);
    }
}
