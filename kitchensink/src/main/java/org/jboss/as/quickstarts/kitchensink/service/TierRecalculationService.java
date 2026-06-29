package org.jboss.as.quickstarts.kitchensink.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.data.OrderRepository;
import org.jboss.as.quickstarts.kitchensink.model.Member;

/**
 * TierRecalculationService — embodies the {@code recalculate_customer_tiers} PL/pgSQL function
 * (db/02_stored_procedures.sql L295-326), now re-expressed entirely in Java.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 → Spring Boot 3.x): the former EJB
 * {@code @Singleton @Startup} bean with an {@code @Schedule(hour = "2")} container timer and a
 * {@code @TransactionAttribute(REQUIRED)} demarcation has been replaced by Spring stereotypes and
 * scheduling. Specifically:
 * <ul>
 *   <li>{@code @Singleton}/{@code @Startup} EJB → {@code @Service} singleton bean;</li>
 *   <li>{@code @Schedule(hour = "2", minute = "0", second = "0")} → {@code @Scheduled(cron = "0 0 2 * * *")}
 *       (nightly at 02:00);</li>
 *   <li>{@code @Startup} (run once when the singleton initializes) →
 *       {@code @EventListener(ApplicationReadyEvent.class)} (run once when the context is ready);</li>
 *   <li>{@code @TransactionAttribute(REQUIRED)} → Spring {@code @Transactional} at the public entry
 *       methods;</li>
 *   <li>{@code @Inject EntityManager} executing {@code SELECT recalculate_customer_tiers()} as a native
 *       query → injected Spring Data repositories plus the tier logic computed in Java (no native
 *       stored-procedure call remains);</li>
 *   <li>CDI-injected {@code java.util.logging.Logger} → SLF4J {@link Logger}.</li>
 * </ul>
 * {@code @EnableScheduling} lives on {@code KitchensinkApplication} (the root bootstrap class); it is
 * required for the {@code @Scheduled} nightly job to fire.</p>
 *
 * <p>Tier thresholds on the 90-day rolling CONFIRMED spend (matching the stored procedure's CASE):
 * BRONZE &lt; 500, SILVER &gt;= 500, GOLD &gt;= 2000, PLATINUM &gt;= 5000.</p>
 */
@Service
public class TierRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(TierRecalculationService.class);

    // Tier floors on 90-day CONFIRMED spend, mirroring the SQL CASE in recalculate_customer_tiers.
    private static final BigDecimal PLATINUM_THRESHOLD = new BigDecimal("5000");
    private static final BigDecimal GOLD_THRESHOLD     = new BigDecimal("2000");
    private static final BigDecimal SILVER_THRESHOLD   = new BigDecimal("500");

    private final MemberRepository memberRepository;
    private final OrderRepository orderRepository;

    // Constructor injection (replaces CDI @Inject field injection); no @Autowired needed for a
    // single constructor. Both collaborators are final/immutable.
    public TierRecalculationService(MemberRepository memberRepository, OrderRepository orderRepository) {
        this.memberRepository = memberRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * Nightly tier recalculation at 02:00.
     *
     * <p>Replaces the EJB {@code @Schedule(hour = "2", minute = "0", second = "0", persistent = false)}
     * timer with Spring's cron-based {@code @Scheduled}. Requires {@code @EnableScheduling} (declared on
     * {@code KitchensinkApplication}).</p>
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void runNightlyTierRecalculation() {
        log.info("TierRecalculationService: starting nightly tier recalculation");
        recalculateAll();
        log.info("TierRecalculationService: nightly tier recalculation complete");
    }

    /**
     * Application-readiness hook replacing the legacy EJB {@code @Startup}.
     *
     * <p>The legacy {@code @Singleton @Startup} bean was only eagerly instantiated at deployment and
     * performed NO recalculation on boot (it declared no {@code @PostConstruct} work); tier
     * recalculation ran solely on the nightly {@code @Schedule(hour="2")} timer. To preserve that
     * observable behavior, this method performs no recalculation at startup and only logs readiness.
     * Recalculation runs on the nightly {@link #runNightlyTierRecalculation()} schedule or via the
     * explicit {@link #triggerRecalculation()} entry point.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("TierRecalculationService: application ready; nightly tier recalculation scheduled for "
                + "02:00 (no recalculation performed at startup)");
    }

    /**
     * Programmatic trigger for an on-demand recalculation.
     *
     * <p>Kept public because {@code TierRecalculationIT} invokes it directly to assert tier transitions.</p>
     */
    @Transactional
    public void triggerRecalculation() {
        log.info("TierRecalculationService: manual recalculation triggered");
        recalculateAll();
        log.info("TierRecalculationService: manual recalculation complete");
    }

    /**
     * Single shared implementation of {@code recalculate_customer_tiers()}.
     *
     * <p>Intentionally {@code private}: it runs inside the transaction established by whichever public
     * caller invoked it. It is NOT annotated {@code @Transactional} because Spring's proxy-based
     * transaction advice is bypassed on self-invocation (an internal call would not pass through the
     * proxy), so a {@code @Transactional} here would be silently ineffective.</p>
     *
     * <p>For every member, it sums the member's CONFIRMED order totals within the trailing 90 days
     * (orders older than the cutoff are excluded), derives the tier from that spend, and persists the
     * change only when the computed tier differs from the stored tier — mirroring the SQL
     * {@code WHERE tier IS DISTINCT FROM new_tier} guard.</p>
     */
    private void recalculateAll() {
        // SQL: o.created_at >= NOW() - INTERVAL '90 days'.
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        for (Member member : memberRepository.findAll()) {
            // SQL: SELECT COALESCE(SUM(o.total), 0) ... WHERE status = 'CONFIRMED' AND created_at >= cutoff.
            BigDecimal spend90d = orderRepository.sumConfirmedTotalSince(member.getId(), cutoff);
            if (spend90d == null) {
                spend90d = BigDecimal.ZERO; // COALESCE(SUM(total), 0) — defensive null guard.
            }
            String newTier = tierForSpend(spend90d);
            // UPDATE only when the tier actually changes (SQL: WHERE tier IS DISTINCT FROM new_tier).
            if (!newTier.equals(member.getTier())) {
                member.setTier(newTier);
                member.setTierUpdatedAt(LocalDateTime.now());
                memberRepository.save(member);
            }
        }
    }

    /**
     * Maps a 90-day spend amount to a loyalty tier, matching the stored procedure's CASE expression.
     *
     * @param spend the trailing-90-day CONFIRMED order spend (never {@code null})
     * @return the tier name: {@code PLATINUM}, {@code GOLD}, {@code SILVER}, or {@code BRONZE}
     */
    private String tierForSpend(BigDecimal spend) {
        if (spend.compareTo(PLATINUM_THRESHOLD) >= 0) {
            return "PLATINUM";
        } else if (spend.compareTo(GOLD_THRESHOLD) >= 0) {
            return "GOLD";
        } else if (spend.compareTo(SILVER_THRESHOLD) >= 0) {
            return "SILVER";
        }
        return "BRONZE";
    }
}
