package org.jboss.as.quickstarts.kitchensink.users;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the users-service (member/identity bounded context).
 *
 * <p>Owns the {@code member} table in the shared PostgreSQL database (logical ownership;
 * {@code ddl-auto=validate}). Runs on port 8083 under context-path {@code /users}.</p>
 *
 * <p>Cross-service role: BOTH a PRODUCER and a CONSUMER:
 * <ul>
 *   <li>PRODUCER — Contract 2 (Tier): {@code GET /api/members/{memberId}/tier} returns
 *       {@code {"tier":"GOLD"}} (producer DTO {@code MemberTierResponse}).</li>
 *   <li>CONSUMER — Contract 3 (Spend): the {@code OrdersClient} HTTP gateway calls orders-service
 *       {@code GET {orders.base-url}/internal/members/{memberId}/spend?days=} (consumer DTO
 *       {@code MemberSpendDto}). This HTTP client is the ONLY legal cross-domain channel.</li>
 * </ul></p>
 *
 * <p>{@code @EnableScheduling} is declared here because users-service is the ONLY service that
 * runs scheduled work: the nightly {@code @Scheduled(cron="0 0 2 * * *")} tier recalculation in
 * {@code service.TierRecalculationService} (single-node semantics, Helm replicas: 1).</p>
 *
 * <p>This class sits at the domain-root package so Spring Boot's default component scan reaches
 * the {@code model}, {@code repository}, {@code service}, {@code rest}, {@code client},
 * {@code dto}, and {@code exception} sub-packages without any explicit scan configuration.</p>
 */
@SpringBootApplication
@EnableScheduling
public class UsersServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsersServiceApplication.class, args);
    }
}
