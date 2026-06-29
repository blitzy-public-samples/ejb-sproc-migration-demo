package org.jboss.as.quickstarts.kitchensink.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import org.jboss.as.quickstarts.kitchensink.data.DiscountAuditRepository;
import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.model.DiscountAudit;
import org.jboss.as.quickstarts.kitchensink.model.Member;

/**
 * DiscountService — pure-Java embodiment of the {@code apply_customer_discount(member_id, base_total)}
 * PL/pgSQL stored procedure (db/02_stored_procedures.sql L111-146), and a key cross-dependency bean.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): formerly a CDI
 * {@code @ApplicationScoped} bean that delegated to a native {@code SELECT apply_customer_discount(...)}
 * query (and a JPQL {@code SELECT m.tier ...} lookup) through an injected {@code EntityManager}. It is
 * now a Spring {@code @Service} that computes the discount entirely in Java from repository-loaded data
 * and persists the audit row via Spring Data JPA. There is NO {@code EntityManager}, NO native
 * stored-procedure call, and NO JPQL. The stored procedure remains in db/02_stored_procedures.sql only
 * as a behavioral reference and is no longer invoked by the application.</p>
 *
 * <p>Collaborators are supplied by constructor injection (replacing CDI {@code @Inject} field
 * injection): {@link MemberRepository} for the member/tier lookup, {@link DiscountAuditRepository} for
 * the audit write, and the shared, stateless {@link PricingService} — the leaf singleton also injected
 * by {@code VendorSelectionService} and {@code OrderService} (AAP §0.6.3).</p>
 *
 * <p>OBSERVABLE SIDE EFFECT PRESERVED: every {@link #calculateDiscount} call inserts EXACTLY ONE
 * {@code discount_audit} row — mirroring the stored procedure's {@code INSERT INTO discount_audit ...}.
 * This is asserted by {@code DiscountServiceIT} (the audit count must increase by exactly one per call)
 * and must never be dropped from any path nor double-counted.</p>
 *
 * <p>No class-level (or method-level) {@code @Transactional} is declared: Spring Data's
 * {@code JpaRepository.save(...)} is itself transactional, so a standalone {@code calculateDiscount}
 * call commits the audit row on its own (the non-transactional preview path), while inside
 * {@code OrderService.submitOrder} (which is {@code @Transactional}) the same {@code save(...)} simply
 * joins the caller's surrounding transaction.</p>
 */
@Service
public class DiscountService {

    // Immutable collaborators (replaces CDI @Inject field injection).
    private final MemberRepository memberRepository;
    private final DiscountAuditRepository discountAuditRepository;
    private final PricingService pricingService;

    /**
     * Single constructor → Spring performs constructor injection automatically (no {@code @Autowired}
     * required). Replaces the former CDI {@code @Inject EntityManager} / {@code @Inject PricingService}
     * field injection.
     *
     * @param memberRepository         repository providing the member and its loyalty tier
     * @param discountAuditRepository  repository used to persist the one-per-call audit row
     * @param pricingService           shared, stateless pricing collaborator (line-total computation)
     */
    public DiscountService(MemberRepository memberRepository,
                           DiscountAuditRepository discountAuditRepository,
                           PricingService pricingService) {
        this.memberRepository = memberRepository;
        this.discountAuditRepository = discountAuditRepository;
        this.pricingService = pricingService;
    }

    /**
     * Calculates the discount AMOUNT for a member on a given base total and persists exactly one
     * {@code discount_audit} row — the Java re-implementation of {@code apply_customer_discount}.
     *
     * <p>The member must exist; a missing member is the Java equivalent of the stored procedure's
     * {@code IF NOT FOUND THEN RAISE EXCEPTION ... ERRCODE = 'P0002'} and surfaces as
     * {@link MemberNotFoundException}. A member that exists but has a {@code null} tier does NOT throw —
     * it falls through to the BRONZE default (2%). The amount is {@code ROUND(base_total * pct, 2)}.</p>
     *
     * @param memberId   the member ID
     * @param baseTotal  the base order total before discount
     * @return           the discount amount (not the percentage), rounded to scale 2 (HALF_UP)
     * @throws MemberNotFoundException if the member does not exist (≙ stored procedure ERRCODE 'P0002')
     */
    public BigDecimal calculateDiscount(Long memberId, BigDecimal baseTotal) {
        // Member must exist (SP: NOT FOUND => RAISE 'P0002'); a present member with a null tier falls
        // through to BRONZE, so we fetch the entity and read getTier() rather than mapping to throw.
        Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new MemberNotFoundException("Member not found: " + memberId));

        BigDecimal pct = discountPctForTier(member.getTier());
        // discountAmt = baseTotal * pct, ROUND(..., 2) — matches discount_amt NUMERIC(12,2).
        BigDecimal discountAmt = baseTotal.multiply(pct).setScale(2, RoundingMode.HALF_UP);

        // Persist EXACTLY ONE audit row (observable side effect — must not be dropped or duplicated).
        // Column scales (db/01_schema.sql): base_total NUMERIC(12,2), discount_pct NUMERIC(5,4),
        // discount_amt NUMERIC(12,2), applied_at TIMESTAMP.
        DiscountAudit audit = new DiscountAudit();
        audit.setMemberId(memberId);
        audit.setBaseTotal(baseTotal.setScale(2, RoundingMode.HALF_UP));
        audit.setDiscountPct(pct.setScale(4, RoundingMode.HALF_UP)); // e.g. 0.0800
        audit.setDiscountAmt(discountAmt);
        audit.setAppliedAt(LocalDateTime.now());
        discountAuditRepository.save(audit);

        return discountAmt;
    }

    /**
     * Calculates the discounted line total for a member purchasing a product. Prices the line via the
     * shared {@link PricingService} then applies the member discount (which also writes one audit row,
     * preserving the original behavior).
     *
     * @param memberId   the member ID
     * @param productId  the product ID
     * @param vendorId   the vendor ID
     * @param quantity   the order quantity
     * @return           the discounted line total
     */
    public BigDecimal getDiscountedLineTotal(Long memberId, Long productId, Long vendorId, int quantity) {
        BigDecimal lineTotal = pricingService.calculateLineTotal(productId, vendorId, quantity);
        BigDecimal discount = calculateDiscount(memberId, lineTotal);
        return lineTotal.subtract(discount);
    }

    /**
     * Returns the loyalty tier string for a member.
     *
     * <p>The member must exist (≙ stored procedure ERRCODE 'P0002'); the tier itself may be {@code null}
     * and is returned as-is. The member is fetched first and the tier read afterward so a {@code null}
     * tier is NOT conflated with a missing member.</p>
     *
     * @param memberId  the member ID
     * @return          the tier string (BRONZE, SILVER, GOLD, PLATINUM), possibly {@code null}
     * @throws MemberNotFoundException if the member does not exist
     */
    public String getMemberTier(Long memberId) {
        return memberRepository.findById(memberId)
            .orElseThrow(() -> new MemberNotFoundException("Member not found: " + memberId))
            .getTier();
    }

    /**
     * Maps a loyalty tier to its discount fraction, mirroring the stored procedure's {@code CASE}
     * expression. Unknown or {@code null} tiers fall through to the BRONZE default of 0.02.
     * (Source A and Source B agree here: PLATINUM 12%, GOLD 8%, SILVER 5%, else 2%.)
     *
     * @param tier the member tier string (may be {@code null})
     * @return the discount fraction (0.12, 0.08, 0.05, or 0.02)
     */
    private BigDecimal discountPctForTier(String tier) {
        if ("PLATINUM".equals(tier)) {
            return new BigDecimal("0.12");
        } else if ("GOLD".equals(tier)) {
            return new BigDecimal("0.08");
        } else if ("SILVER".equals(tier)) {
            return new BigDecimal("0.05");
        }
        return new BigDecimal("0.02"); // BRONZE / null / any other
    }
}
