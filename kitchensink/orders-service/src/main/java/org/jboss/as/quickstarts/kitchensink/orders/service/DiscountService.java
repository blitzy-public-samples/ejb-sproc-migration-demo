package org.jboss.as.quickstarts.kitchensink.orders.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.jboss.as.quickstarts.kitchensink.orders.client.UsersClient;
import org.jboss.as.quickstarts.kitchensink.orders.model.DiscountAudit;
import org.jboss.as.quickstarts.kitchensink.orders.repository.DiscountAuditRepository;
import org.springframework.stereotype.Service;

/**
 * Applies tier-based customer discounts. Pure-Java reimplementation of the
 * {@code apply_customer_discount} PL/pgSQL stored procedure
 * ({@code db/02_stored_procedures.sql} L111-146); no native query is ever
 * executed. The member tier is resolved over HTTP via {@link UsersClient}
 * (Contract 2), never by reading a {@code Member} entity directly (orders-service
 * owns no {@code Member}, AAP &sect;0.7.2 boundary rule).
 *
 * <p>Replaces the monolith {@code @ApplicationScoped} bean that injected an
 * {@code EntityManager} (native {@code SELECT apply_customer_discount(...)} plus a
 * JPQL {@code SELECT m.tier} member read) and a {@code PricingService}. Those
 * collaborators are gone: pricing now lives in marketplace-service and the tier
 * read is externalized to {@link UsersClient}.</p>
 */
@Service
public class DiscountService {

    /** PLATINUM tier discount rate (12%), stored as a fraction. */
    private static final BigDecimal PLATINUM_PCT = new BigDecimal("0.12");
    /** GOLD tier discount rate (8%), stored as a fraction. */
    private static final BigDecimal GOLD_PCT     = new BigDecimal("0.08");
    /** SILVER tier discount rate (5%), stored as a fraction. */
    private static final BigDecimal SILVER_PCT   = new BigDecimal("0.05");
    /** BRONZE (and any unknown tier) discount rate (2%), stored as a fraction. */
    private static final BigDecimal BRONZE_PCT   = new BigDecimal("0.02");

    private final UsersClient usersClient;
    private final DiscountAuditRepository discountAuditRepository;

    /**
     * Constructor injection of the two collaborators (Spring-managed singletons).
     *
     * @param usersClient             cross-domain gateway to users-service for the member tier (Contract 2)
     * @param discountAuditRepository Spring Data repository used to persist the per-call audit row
     */
    public DiscountService(UsersClient usersClient, DiscountAuditRepository discountAuditRepository) {
        this.usersClient = usersClient;
        this.discountAuditRepository = discountAuditRepository;
    }

    /**
     * Reimplements {@code apply_customer_discount(p_member_id, p_base_total)} faithfully.
     *
     * <p>Steps (identical arithmetic, rounding, and side-effects to the stored procedure):</p>
     * <ol>
     *   <li>Resolve the member tier via {@link UsersClient#getMemberTier(Long)}. A producer 404
     *       surfaces as {@code MemberNotFoundException} and a 5xx as
     *       {@code ServiceUnavailableException}; both are allowed to propagate (this replaces the
     *       procedure's {@code P0002} "Member not found"), so they are intentionally NOT caught.</li>
     *   <li>Map the tier to its discount fraction (PLATINUM&nbsp;0.12 / GOLD&nbsp;0.08 /
     *       SILVER&nbsp;0.05 / else&nbsp;0.02).</li>
     *   <li>Compute {@code discountAmt = ROUND(baseTotal &times; pct, 2)} using
     *       {@link RoundingMode#HALF_UP}.</li>
     *   <li>Persist exactly ONE {@link DiscountAudit} row for this call.</li>
     *   <li>Return the discount amount.</li>
     * </ol>
     *
     * <p><strong>Side-effect preservation (AAP &sect;0.6.3):</strong> this method is invoked by both
     * {@code OrderService.previewOrder} (non-transactional) and {@code OrderService.submitOrder}
     * ({@code @Transactional}). The stored procedure inserts an audit row on every call, including
     * non-committing previews, and that quirk is preserved, not "fixed". This method (and the class)
     * is deliberately NOT annotated {@code @Transactional}: when called from the preview path,
     * {@code save} runs in its own implicit transaction and auto-commits the audit row per
     * invocation; when called from {@code submitOrder}, {@code save} joins that method's
     * transaction. No guard is added to skip the audit on previews.</p>
     *
     * @param memberId  the member identifier whose tier drives the discount rate
     * @param baseTotal the base order total before discount
     * @return the discount amount (not the percentage), rounded to 2 decimal places
     */
    public BigDecimal calculateDiscount(Long memberId, BigDecimal baseTotal) {
        // 1. Tier over HTTP (replaces "SELECT tier FROM member"); 404 -> MemberNotFoundException.
        String tier = usersClient.getMemberTier(memberId);

        // 2. & 3. Tier -> fraction, then discountAmt = ROUND(baseTotal * pct, 2).
        BigDecimal discountPct = discountPctForTier(tier);
        BigDecimal discountAmt = baseTotal.multiply(discountPct).setScale(2, RoundingMode.HALF_UP);

        // 4. Persist exactly ONE audit row per call (the preserved cross-cutting side-effect).
        DiscountAudit audit = new DiscountAudit();
        audit.setMemberId(memberId);
        audit.setBaseTotal(baseTotal);
        audit.setDiscountPct(discountPct);
        audit.setDiscountAmt(discountAmt);
        audit.setAppliedAt(LocalDateTime.now());
        discountAuditRepository.save(audit);

        // 5. Return the discount amount.
        return discountAmt;
    }

    /**
     * Maps a loyalty tier token to its discount fraction, mirroring the stored procedure's
     * {@code CASE} statement. The {@code else} branch matches the procedure's {@code ELSE} and
     * therefore covers BRONZE as well as any unknown / null tier value.
     *
     * @param tier the tier token (expected {@code BRONZE}/{@code SILVER}/{@code GOLD}/{@code PLATINUM})
     * @return the discount fraction as a {@link BigDecimal}
     */
    private BigDecimal discountPctForTier(String tier) {
        if ("PLATINUM".equals(tier)) {
            return PLATINUM_PCT;
        } else if ("GOLD".equals(tier)) {
            return GOLD_PCT;
        } else if ("SILVER".equals(tier)) {
            return SILVER_PCT;
        } else {
            // BRONZE and any unknown tier -> 0.02 (matches the procedure's ELSE branch).
            return BRONZE_PCT;
        }
    }
}
