package org.jboss.as.quickstarts.kitchensink.users;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the users-service.
 *
 * <p>Bounded context: member registration and loyalty-tier management.
 * Owns the {@code member} table ONLY. Runs on port 8083 under context-path
 * {@code /users} (see application.properties).</p>
 *
 * <p>This class sits in the base package {@code org.jboss.as.quickstarts.kitchensink.users}
 * so {@code @SpringBootApplication} component-scans the model, repository, service, client,
 * rest, dto, config, and exception sub-packages.</p>
 *
 * <p>users-service is both a producer (member CRUD, the Contract-2 tier endpoint, and the
 * GAP-3 spend-increment endpoint) and a consumer (it calls orders-service over HTTP via the
 * thin components in the client sub-package, reusing the shared HTTP client bean provided by
 * the config sub-package).</p>
 *
 * <p><b>{@code @EnableScheduling} is present here and is the single most important difference
 * from the sibling MarketplaceApplication/OrdersApplication classes</b> (which deliberately
 * omit it). It activates the nightly customer-tier recalculation in
 * {@code TierRecalculationService} (a cron-scheduled task at 02:00 server time, cron
 * {@code 0 0 2 * * *}), which lives exclusively in users-service. Without it the nightly job
 * would never fire.</p>
 */
@SpringBootApplication
@EnableScheduling
public class UsersApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsersApplication.class, args);
    }
}
