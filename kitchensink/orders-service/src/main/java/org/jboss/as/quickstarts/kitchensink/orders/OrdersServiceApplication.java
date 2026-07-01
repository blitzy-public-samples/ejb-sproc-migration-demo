package org.jboss.as.quickstarts.kitchensink.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the orders-service (orders / cart / shipping / discount bounded context).
 *
 * <p>Owns the {@code orders}, {@code order_items}, {@code order_draft_items}, {@code shipping_zones},
 * and {@code discount_audit} tables in the shared PostgreSQL database (logical ownership;
 * {@code ddl-auto=validate}). Runs on port 8082 under context-path {@code /orders}.</p>
 *
 * <p>Cross-service role: BOTH a CONSUMER and a PRODUCER.
 * <ul>
 *   <li>CONSUMER — Contract 1 (Pricing): {@code client.MarketplaceClient} calls marketplace-service
 *       {@code GET {marketplace.base-url}/api/products/{productId}/price?vendorId=&qty=}.</li>
 *   <li>CONSUMER — Contract 2 (Tier): {@code client.UsersClient} calls users-service
 *       {@code GET {users.base-url}/api/members/{memberId}/tier}.</li>
 *   <li>PRODUCER — Contract 3 (Spend): {@code rest.InternalOrderResourceRESTService} exposes
 *       {@code GET /internal/members/{memberId}/spend?days=} returning {@code {"totalSpend":...}}.</li>
 * </ul>
 * Those HTTP clients are the ONLY legal cross-domain channel (boundary rule, AAP §0.7.2).</p>
 *
 * <p>This class deliberately does NOT declare {@code @EnableScheduling} — scheduled work
 * (nightly tier recalculation) lives ONLY in users-service. orders-service is a plain
 * {@code @SpringBootApplication}.</p>
 *
 * <p>It sits at the domain-root package so Spring Boot's default component scan reaches the
 * {@code model}, {@code repository}, {@code service}, {@code rest}, {@code client}, {@code dto},
 * and {@code exception} sub-packages without any explicit scan configuration.</p>
 */
@SpringBootApplication
public class OrdersServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrdersServiceApplication.class, args);
    }
}
