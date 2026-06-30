package org.jboss.as.quickstarts.kitchensink.users.service;

import org.jboss.as.quickstarts.kitchensink.users.client.OrdersClient;
import org.jboss.as.quickstarts.kitchensink.users.model.Member;
import org.jboss.as.quickstarts.kitchensink.users.repository.MemberRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Nightly loyalty-tier recalculation for users-service — the pure-Java reimplementation of the
 * monolith's {@code recalculate_customer_tiers()} stored procedure (db/02_stored_procedures.sql).
 *
 * <p>The procedure is NEVER invoked at runtime; this class reproduces its logic in Java. The only
 * decomposition change is that each member's 90-day rolling spend is read from orders-service over
 * HTTP via {@link OrdersClient} (Contract 3) instead of a SQL sub-select on the {@code orders}
 * table (boundary rule, AAP &sect;0.7.2).</p>
 *
 * <p>EJB timer conversion (AAP &sect;0.4.1): the legacy {@code @Singleton @Startup}
 * {@code @Schedule(hour="2")} EJB becomes a Spring {@code @Scheduled(cron="0 0 2 * * *")} nightly
 * job plus an {@code @EventListener(ApplicationReadyEvent)} startup trigger. Scheduling is enabled
 * by {@code @EnableScheduling} on {@code UsersServiceApplication}; single-node semantics are
 * retained (Helm replicas: 1, AAP &sect;0.6.5/&sect;0.7.2).</p>
 *
 * <p>Tier thresholds (90-day rolling spend): &ge;5000 PLATINUM, &ge;2000 GOLD, &ge;500 SILVER, else
 * BRONZE. The tier is driven by the 90-day spend, NOT by {@code member.totalSpend} (lifetime).</p>
 */
@Service
public class TierRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(TierRecalculationService.class);

    private static final BigDecimal PLATINUM_THRESHOLD = new BigDecimal("5000");
    private static final BigDecimal GOLD_THRESHOLD = new BigDecimal("2000");
    private static final BigDecimal SILVER_THRESHOLD = new BigDecimal("500");

    private final MemberRepository memberRepository;
    private final OrdersClient ordersClient;

    public TierRecalculationService(MemberRepository memberRepository, OrdersClient ordersClient) {
        this.memberRepository = memberRepository;
        this.ordersClient = ordersClient;
    }

    /**
     * Nightly scheduled job (02:00, single node). Replaces the EJB {@code @Schedule(hour="2",
     * minute="0", second="0", persistent=false)}.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void runNightlyTierRecalculation() {
        log.info("TierRecalculationService: starting nightly tier recalculation");
        recalculateAllTiers();
        log.info("TierRecalculationService: nightly tier recalculation complete");
    }

    /**
     * Startup trigger. Replaces the EJB {@code @Startup} eager-activation semantics so an initial
     * recalculation runs once the application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onApplicationReady() {
        log.info("TierRecalculationService: startup tier recalculation triggered");
        recalculateAllTiers();
        log.info("TierRecalculationService: startup tier recalculation complete");
    }

    /**
     * Manually invocable recalculation (retained from the monolith; used by integration tests).
     */
    @Transactional
    public void triggerRecalculation() {
        log.info("TierRecalculationService: manual recalculation triggered");
        recalculateAllTiers();
        log.info("TierRecalculationService: manual recalculation complete");
    }

    /**
     * Core loop — faithful Java port of {@code recalculate_customer_tiers()}. Runs within the
     * caller's transaction (it is a private helper, so it is intentionally NOT a self-invoked
     * {@code @Transactional} method — that would bypass the Spring proxy).
     */
    private void recalculateAllTiers() {
        for (Member member : memberRepository.findAll()) {
            BigDecimal spend90d = ordersClient.getNinetyDaySpend(member.getId());
            if (spend90d == null) {
                // Mirrors SQL COALESCE(SUM(o.total), 0); also guards against a null from a
                // test stub during the startup trigger.
                spend90d = BigDecimal.ZERO;
            }
            String newTier = determineTier(spend90d);
            if (!newTier.equals(member.getTier())) {   // SQL: tier IS DISTINCT FROM v_new_tier
                member.setTier(newTier);
                member.setTierUpdatedAt(LocalDateTime.now());   // SQL: tier_updated_at = NOW()
                memberRepository.save(member);
            }
        }
    }

    private String determineTier(BigDecimal spend90d) {
        if (spend90d.compareTo(PLATINUM_THRESHOLD) >= 0) {
            return "PLATINUM";
        } else if (spend90d.compareTo(GOLD_THRESHOLD) >= 0) {
            return "GOLD";
        } else if (spend90d.compareTo(SILVER_THRESHOLD) >= 0) {
            return "SILVER";
        } else {
            return "BRONZE";
        }
    }
}
