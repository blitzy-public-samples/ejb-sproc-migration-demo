package org.jboss.as.quickstarts.kitchensink.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * <p>The AAP maps the EJB {@code @Singleton @Startup} lifecycle to Spring's
     * {@code @EventListener(ApplicationReadyEvent.class)} (§0.1.2, §0.3.2). This handler runs the tier
     * recalculation exactly once, immediately after the application context is fully initialized — by
     * the time {@link ApplicationReadyEvent} is published the datasource, the JPA layer, and any SQL
     * data-initialization have all completed — so loyalty tiers reflect the current 90-day CONFIRMED
     * spend as soon as the service is ready to receive traffic, rather than waiting for the first
     * nightly {@link #runNightlyTierRecalculation()} run at 02:00.</p>
     *
     * <p>It delegates to the same {@link #recalculateAll()} implementation used by the scheduled and
     * manual entry points, so the extracted {@code recalculate_customer_tiers} logic is identical
     * across all three triggers — only the invocation timing is extended to application startup, which
     * is precisely what the {@code @EventListener(ApplicationReadyEvent)} component is intended to
     * provide.</p>
     *
     * <p>{@code @Transactional} is required here because the readiness event is published outside any
     * ambient transaction; it establishes the transaction that {@link #recalculateAll()} relies on for
     * its batched read/update. The event adapter invokes this method through the Spring proxy, so the
     * transactional advice is applied (this is not a self-invocation).</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onApplicationReady() {
        log.info("TierRecalculationService: application ready; running startup tier recalculation");
        recalculateAll();
        log.info("TierRecalculationService: startup tier recalculation complete "
                + "(nightly recalculation remains scheduled for 02:00)");
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

        // ONE grouped aggregate query for ALL members' 90-day CONFIRMED spend, replacing the former
        // per-member orderRepository.sumConfirmedTotalSince(...) call (an N+1 aggregate pattern that
        // issued one query per member and became pathological at scale). Members with NO qualifying
        // CONFIRMED orders in the window are simply absent from the result and are defaulted to zero
        // in memory below — preserving the stored procedure's COALESCE(SUM(o.total), 0) semantics.
        Map<Long, BigDecimal> spendByMember = new HashMap<>();
        for (OrderRepository.MemberSpendProjection row : orderRepository.sumConfirmedTotalsByMemberSince(cutoff)) {
            BigDecimal spend = row.getTotalSpend();
            spendByMember.put(row.getMemberId(), spend != null ? spend : BigDecimal.ZERO);
        }

        // Recompute each member's tier from the preloaded spend map and collect ONLY those whose tier
        // actually changes (SQL: WHERE tier IS DISTINCT FROM new_tier), then persist them in a single
        // batch save. A single timestamp is used for every change in this run, mirroring the stored
        // procedure's NOW() which is constant within its transaction.
        List<Member> changed = new ArrayList<>();
        LocalDateTime updatedAt = LocalDateTime.now();
        for (Member member : memberRepository.findAll()) {
            // COALESCE(SUM(total), 0): absent member -> 0 (no qualifying orders in the 90-day window).
            BigDecimal spend90d = spendByMember.getOrDefault(member.getId(), BigDecimal.ZERO);
            String newTier = tierForSpend(spend90d);
            if (!newTier.equals(member.getTier())) {
                member.setTier(newTier);
                member.setTierUpdatedAt(updatedAt);
                changed.add(member);
            }
        }
        // Batch-save only the members whose tier changed (no write at all when nothing changed).
        if (!changed.isEmpty()) {
            memberRepository.saveAll(changed);
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
