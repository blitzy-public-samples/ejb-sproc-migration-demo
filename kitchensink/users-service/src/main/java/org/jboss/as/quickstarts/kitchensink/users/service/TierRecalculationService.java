package org.jboss.as.quickstarts.kitchensink.users.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.jboss.as.quickstarts.kitchensink.users.client.OrdersClient;
import org.jboss.as.quickstarts.kitchensink.users.model.Member;
import org.jboss.as.quickstarts.kitchensink.users.repository.MemberRepository;

/**
 * TierRecalculationService - faithful Java extraction of the recalculate_customer_tiers()
 * PL/pgSQL stored procedure (db/02_stored_procedures.sql, lines 295-326). The stored procedure
 * is retained as reference documentation only and is no longer invoked (zero native queries).
 *
 * <p>Recomputes each member's loyalty tier from their trailing 90-day CONFIRMED-order spend,
 * which is now fetched over HTTP from orders-service via {@link OrdersClient} (Contract 3)
 * instead of the in-database orders SUM. Runs nightly at 02:00 server time via {@code @Scheduled}
 * (scheduling enabled by {@code @EnableScheduling} on UsersApplication); a manual trigger is also
 * exposed.</p>
 *
 * <p>Tier thresholds based on 90-day rolling spend (preserved EXACTLY):
 * BRONZE &lt; $500; SILVER $500 - $1,999.99; GOLD $2,000 - $4,999.99; PLATINUM &gt;= $5,000.</p>
 *
 * <p>Cross-service HTTP ({@code getMemberSpend}) occurs OUTSIDE any {@code @Transactional}
 * boundary; each per-member {@code save} is a normal Spring Data write.</p>
 */
@Service
public class TierRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(TierRecalculationService.class);

    // Loyalty-tier thresholds preserved EXACTLY from recalculate_customer_tiers (SQL L311-316).
    private static final BigDecimal PLATINUM_THRESHOLD = new BigDecimal("5000");
    private static final BigDecimal GOLD_THRESHOLD     = new BigDecimal("2000");
    private static final BigDecimal SILVER_THRESHOLD   = new BigDecimal("500");

    // 90-day rolling spend window (SQL: created_at >= NOW() - INTERVAL '90 days').
    private static final int SPEND_WINDOW_DAYS = 90;

    // Constructor injection (single constructor -> no @Autowired required).
    private final MemberRepository memberRepository;
    private final OrdersClient ordersClient;

    public TierRecalculationService(MemberRepository memberRepository, OrdersClient ordersClient) {
        this.memberRepository = memberRepository;
        this.ordersClient = ordersClient;
    }

    /**
     * Nightly scheduled job (02:00 server time). Replaces the EJB @Schedule(hour="2") timer.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void runNightlyTierRecalculation() {
        log.info("TierRecalculationService: starting nightly tier recalculation");
        recalculateAllTiers();
        log.info("TierRecalculationService: nightly tier recalculation complete");
    }

    /**
     * Manual trigger running the same recalculation logic (admin / on-demand use).
     */
    public void triggerRecalculation() {
        log.info("TierRecalculationService: manual recalculation triggered");
        recalculateAllTiers();
        log.info("TierRecalculationService: manual recalculation complete");
    }

    /**
     * Reproduces recalculate_customer_tiers (db/02_stored_procedures.sql L295-326) in Java:
     * for each member, fetch 90-day spend over HTTP, compute the new tier, and persist ONLY when
     * the tier changed (stamping tier_updated_at).
     *
     * <p>NOT @Transactional: the cross-service HTTP call (ordersClient.getMemberSpend) must run
     * outside any transaction boundary (AAP 0.7.2); each changed member is saved individually.</p>
     *
     * <p><b>Per-member failure isolation.</b> The spend lookup fans out one HTTP call per member, so
     * a single downstream failure (e.g. orders-service briefly unavailable -> ServiceUnavailableException)
     * must NOT abort the whole nightly run. Each member is processed in its own try/catch: a failure is
     * logged WITHOUT PII (member id only) and recalculation continues with the remaining members. A
     * summary count of processed/updated/failed members is logged at the end for observability.</p>
     */
    private void recalculateAllTiers() {
        List<Member> members = memberRepository.findAll();
        int processed = 0;
        int updated = 0;
        int failed = 0;
        for (Member member : members) {
            try {
                // Cross-service HTTP (Contract 3) replaces the in-DB 90-day orders SUM. Outside any tx.
                BigDecimal spend = ordersClient.getMemberSpend(member.getId(), SPEND_WINDOW_DAYS);
                String newTier = computeTier(spend);

                // UPDATE ... WHERE tier IS DISTINCT FROM v_new_tier -> persist ONLY when changed (SQL L318-323).
                if (!newTier.equals(member.getTier())) {
                    member.setTier(newTier);
                    member.setTierUpdatedAt(LocalDateTime.now());
                    memberRepository.save(member);
                    updated++;
                }
                processed++;
            } catch (RuntimeException e) {
                // Isolate the failure to this member: log without PII (id only, never name/email) and
                // continue. e.toString() includes only the exception type and a message that itself
                // carries no PII (it references the member id at most).
                failed++;
                log.warn("Tier recalculation skipped for member id={} due to a downstream failure: {}",
                        member.getId(), e.toString());
            }
        }
        log.info("Tier recalculation summary: {} processed, {} updated, {} failed (of {} members)",
                processed, updated, failed, members.size());
    }

    /**
     * CASE on 90-day spend (SQL L311-316), preserved EXACTLY:
     * &gt;= 5000 PLATINUM, &gt;= 2000 GOLD, &gt;= 500 SILVER, else BRONZE.
     */
    private String computeTier(BigDecimal spend) {
        if (spend.compareTo(PLATINUM_THRESHOLD) >= 0) {
            return "PLATINUM";
        } else if (spend.compareTo(GOLD_THRESHOLD) >= 0) {
            return "GOLD";
        } else if (spend.compareTo(SILVER_THRESHOLD) >= 0) {
            return "SILVER";
        } else {
            return "BRONZE";
        }
    }
}
