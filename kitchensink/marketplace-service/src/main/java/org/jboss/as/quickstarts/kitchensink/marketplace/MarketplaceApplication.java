package org.jboss.as.quickstarts.kitchensink.marketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the marketplace-service.
 *
 * <p>Bounded context: product catalog, pricing, and vendor selection.
 * Owns the {@code products}, {@code vendors}, and {@code vendor_inventory} tables.
 * Runs on port 8081 under context-path {@code /marketplace} (see application.properties).</p>
 *
 * <p>This class sits in the base package {@code org.jboss.as.quickstarts.kitchensink.marketplace}
 * so {@code @SpringBootApplication} component-scans the model, repository, service, rest, dto,
 * and exception sub-packages.</p>
 *
 * <p>marketplace-service is a pure producer: it serves HTTP but makes no outbound
 * cross-service HTTP calls. It therefore intentionally wires no outbound HTTP-client
 * bean and no scheduling enablement annotation — there are no scheduled tasks here
 * (the nightly tier-recalculation schedule lives only in users-service).</p>
 */
@SpringBootApplication
public class MarketplaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketplaceApplication.class, args);
    }
}
