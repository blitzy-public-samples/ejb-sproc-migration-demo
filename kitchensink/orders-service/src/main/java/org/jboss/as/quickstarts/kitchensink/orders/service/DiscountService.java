package org.jboss.as.quickstarts.kitchensink.orders.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import org.jboss.as.quickstarts.kitchensink.orders.client.UsersClient;
import org.jboss.as.quickstarts.kitchensink.orders.model.DiscountAudit;
import org.jboss.as.quickstarts.kitchensink.orders.repository.DiscountAuditRepository;

/**
 * Faithful Java extraction of the apply_customer_discount() PL/pgSQL stored procedure
 * (db/02_stored_procedures.sql, lines 111-146). The stored procedure is retained in the
 * repository as reference documentation only and is no longer invoked (zero native queries).
 *
 * Cross-domain rule: the member tier is resolved over HTTP via UsersClient (the member table is
 * owned by users-service); this orders-service class never reads a member row directly.
 */
@Service
public class DiscountService {

    // Discount-rate constants preserved EXACTLY from apply_customer_discount (SQL L120-126).
    private static final BigDecimal PLATINUM_PCT = new BigDecimal("0.12");
    private static final BigDecimal GOLD_PCT     = new BigDecimal("0.08");
    private static final BigDecimal SILVER_PCT   = new BigDecimal("0.05");
    private static final BigDecimal BRONZE_PCT   = new BigDecimal("0.02");

    // Constructor injection (single constructor -> no @Autowired required).
    private final DiscountAuditRepository discountAuditRepository;
    private final UsersClient usersClient;

    public DiscountService(DiscountAuditRepository discountAuditRepository, UsersClient usersClient) {
        this.discountAuditRepository = discountAuditRepository;
        this.usersClient = usersClient;
    }

    /**
     * Reproduces apply_customer_discount (db/02_stored_procedures.sql L111-146):
     * resolve tier -> pct, amt = ROUND(baseTotal * pct, 2), persist a discount_audit row, return amt.
     */
    public BigDecimal calculateDiscount(Long memberId, BigDecimal baseTotal) {
        // Tier resolved over HTTP (Contract 2) instead of the original member-table read.
        String tier = usersClient.getMemberTier(memberId);

        // CASE v_tier WHEN 'PLATINUM' ... ELSE 0.02 (BRONZE) END (SQL L120-126).
        BigDecimal pct;
        if ("PLATINUM".equals(tier)) {
            pct = PLATINUM_PCT;
        } else if ("GOLD".equals(tier)) {
            pct = GOLD_PCT;
        } else if ("SILVER".equals(tier)) {
            pct = SILVER_PCT;
        } else {
            pct = BRONZE_PCT;
        }

        // v_discount_amt := ROUND(p_base_total * v_discount_pct, 2) (SQL L129).
        BigDecimal amt = baseTotal.multiply(pct).setScale(2, RoundingMode.HALF_UP);

        // INSERT INTO discount_audit (...) VALUES (..., NOW()) (SQL L132-141) -- side effect on EVERY call.
        DiscountAudit audit = new DiscountAudit();
        audit.setMemberId(memberId);
        audit.setBaseTotal(baseTotal);
        audit.setDiscountPct(pct);
        audit.setDiscountAmt(amt);
        audit.setAppliedAt(LocalDateTime.now());
        discountAuditRepository.save(audit);

        return amt;
    }
}
