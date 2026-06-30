package org.jboss.as.quickstarts.kitchensink.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the orders-service.
 *
 * <p>Bounded context: shopping cart, order orchestration, discounting, and shipping.
 * Owns the {@code orders}, {@code order_items}, {@code order_draft_items},
 * {@code shipping_zones}, and {@code discount_audit} tables.
 * Runs on port 8082 under context-path {@code /orders} (see application.properties).</p>
 *
 * <p>This class sits in the base package {@code org.jboss.as.quickstarts.kitchensink.orders}
 * so the single composite bootstrap annotation component-scans the model, repository, service,
 * client, rest, dto, config, and exception sub-packages with no extra configuration.</p>
 *
 * <p>orders-service is both a cross-service HTTP consumer (it calls the marketplace and users
 * services through the thin components in the client sub-package, reusing the shared HTTP
 * client bean provided by the config sub-package) and a producer (the Contract-3 internal
 * spend endpoint). Scheduling is intentionally NOT enabled in this service; the nightly
 * tier-recalculation schedule lives ONLY in users-service.</p>
 */
@SpringBootApplication
public class OrdersApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }
}
