package org.jboss.as.quickstarts.kitchensink.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
 * TierRecalculationService — recomputes each member's loyalty tier from their trailing 90-day
 * CONFIRMED-order spend, re-implementing the {@code recalculate_customer_tiers()} PL/pgSQL
 * procedure in Java.
 *
 * <p>Tier thresholds based on 90-day rolling spend (identical to the stored procedure):</p>
 * <ul>
 *   <li>PLATINUM &ge; $5,000</li>
 *   <li>GOLD     &ge; $2,000</li>
 *   <li>SILVER   &ge; $500</li>
 *   <li>BRONZE   &lt; $500</li>
 * </ul>
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): formerly an EJB
 * {@code @Singleton @Startup} bean with an {@code @Schedule(hour="2")} method that delegated to a
 * native {@code SELECT recalculate_customer_tiers()} query. It is now a Spring {@code @Service}:</p>
 * <ul>
 *   <li>{@code @Scheduled(cron="0 0 2 * * *")} replaces {@code @Schedule(hour="2")} for the nightly run.</li>
 *   <li>{@code @EventListener(ApplicationReadyEvent)} replaces {@code @Startup}. IMPORTANT: the legacy
 *       {@code @Startup} singleton performed <em>no work</em> at boot (it had no startup task body); it
 *       merely eagerly instantiated. To preserve that exact behavior, the readiness handler here only
 *       logs — it deliberately does NOT recalculate at startup. Running a recalculation on boot would
 *       overwrite the externally-seeded member tiers (e.g., set the GOLD seed member to BRONZE) and
 *       break the discount/test expectations.</li>
 *   <li>The native procedure call is removed; the loop is performed in Java over Spring Data repositories.</li>
 * </ul>
 *
 * <p>{@code @Transactional} is placed on the externally-invoked entry points
 * ({@link #runNightlyTierRecalculation()} and {@link #triggerRecalculation()}) rather than on the
 * private worker, because Spring's proxy-based transaction advice does not apply to self-invocation.</p>
 */
@Service
public class TierRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(TierRecalculationService.class);

    /** Trailing window, in days, over which CONFIRMED-order spend is summed. */
    private static final int SPEND_WINDOW_DAYS = 90;

    private static final BigDecimal PLATINUM_FLOOR = new BigDecimal("5000");
    private static final BigDecimal GOLD_FLOOR = new BigDecimal("2000");
    private static final BigDecimal SILVER_FLOOR = new BigDecimal("500");

    private final MemberRepository memberRepository;
    private final OrderRepository orderRepository;

    public TierRecalculationService(MemberRepository memberRepository,
                                    OrderRepository orderRepository) {
        this.memberRepository = memberRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * Application-readiness hook replacing the legacy EJB {@code @Startup}. Logs only — it performs
     * NO recalculation, exactly mirroring the original startup behavior (the legacy singleton ran no
     * work on boot). Recalculation runs only on the nightly schedule or when explicitly triggered.
     *
     * @param event the application-ready event (unused beyond signaling readiness)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        log.info("TierRecalculationService: application ready; nightly tier recalculation scheduled for 02:00 "
                + "(no recalculation performed at startup)");
    }

    /**
     * Nightly scheduled recalculation, replacing the EJB {@code @Schedule(hour="2")}.
     * Runs every day at 02:00 server time.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void runNightlyTierRecalculation() {
        log.info("TierRecalculationService: starting nightly tier recalculation");
        int updated = recalculateAllTiers();
        log.info("TierRecalculationService: nightly tier recalculation complete ({} member(s) updated)", updated);
    }

    /**
     * Manually triggers a full tier recalculation. Used by integration tests and any on-demand
     * administrative path. {@code @Transactional} so the recalculation runs in a single transaction.
     *
     * @return the number of members whose tier changed
     */
    @Transactional
    public int triggerRecalculation() {
        log.info("TierRecalculationService: manual recalculation triggered");
        int updated = recalculateAllTiers();
        log.info("TierRecalculationService: manual recalculation complete ({} member(s) updated)", updated);
        return updated;
    }

    /**
     * Core recalculation loop (private worker; runs within the caller's transaction). For each
     * member it sums CONFIRMED order totals over the trailing 90-day window, derives the new tier,
     * and updates the member only when the tier actually changes — mirroring the stored procedure's
     * {@code tier IS DISTINCT FROM v_new_tier} guard and {@code tier_updated_at = NOW()} stamp.
     *
     * @return the number of members whose tier changed
     */
    private int recalculateAllTiers() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(SPEND_WINDOW_DAYS);
        List<Member> members = memberRepository.findAll();
        int updatedCount = 0;

        for (Member member : members) {
            BigDecimal spend90d = orderRepository.sumConfirmedTotalSince(member.getId(), cutoff);
            if (spend90d == null) {
                spend90d = BigDecimal.ZERO;
            }
            String newTier = determineTier(spend90d);

            // Update only if the tier actually changed (≙ tier IS DISTINCT FROM v_new_tier).
            if (!newTier.equals(member.getTier())) {
                member.setTier(newTier);
                member.setTierUpdatedAt(LocalDateTime.now());
                memberRepository.save(member);
                updatedCount++;
            }
        }
        return updatedCount;
    }

    /**
     * Maps a 90-day spend amount to a loyalty tier using the stored procedure's thresholds.
     *
     * @param spend90d trailing 90-day CONFIRMED-order spend (non-null)
     * @return one of PLATINUM, GOLD, SILVER, BRONZE
     */
    private String determineTier(BigDecimal spend90d) {
        if (spend90d.compareTo(PLATINUM_FLOOR) >= 0) {
            return "PLATINUM";
        } else if (spend90d.compareTo(GOLD_FLOOR) >= 0) {
            return "GOLD";
        } else if (spend90d.compareTo(SILVER_FLOOR) >= 0) {
            return "SILVER";
        } else {
            return "BRONZE";
        }
    }
}
