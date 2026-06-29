package org.jboss.as.quickstarts.kitchensink.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jboss.as.quickstarts.kitchensink.data.DiscountAuditRepository;
import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.model.DiscountAudit;
import org.jboss.as.quickstarts.kitchensink.model.Member;

/**
 * DiscountService — pure-Java re-implementation of the {@code apply_customer_discount(member_id,
 * base_total)} PL/pgSQL stored procedure, and a key cross-dependency bean.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): formerly a CDI
 * {@code @ApplicationScoped} bean delegating to a native {@code SELECT apply_customer_discount(...)}
 * query via {@code EntityManager}. It is now a Spring {@code @Service} that computes the discount
 * in Java and persists the audit row via Spring Data, using the shared, stateless
 * {@link PricingService} (also injected by {@code VendorSelectionService} and {@code OrderService}).</p>
 *
 * <p>Tier discount rates (BRONZE 2%, SILVER 5%, GOLD 8%, PLATINUM 12%) and the
 * {@code discount_audit} write are preserved exactly. The audit row written on every
 * {@link #calculateDiscount} call is an observable side effect the migration must keep.</p>
 */
@Service
public class DiscountService {

    private final MemberRepository memberRepository;
    private final DiscountAuditRepository discountAuditRepository;
    private final PricingService pricingService;

    public DiscountService(MemberRepository memberRepository,
                           DiscountAuditRepository discountAuditRepository,
                           PricingService pricingService) {
        this.memberRepository = memberRepository;
        this.discountAuditRepository = discountAuditRepository;
        this.pricingService = pricingService;
    }

    /**
     * Calculates the discount AMOUNT for a member on a given base total and persists a
     * {@code discount_audit} row, mirroring {@code apply_customer_discount}.
     *
     * <p>Declared {@code @Transactional} so the audit insert participates in the caller's
     * transaction when invoked from {@code OrderService.submitOrder}, and commits atomically when
     * invoked standalone. The percentage is selected by tier
     * (PLATINUM=0.12, GOLD=0.08, SILVER=0.05, otherwise BRONZE=0.02) and the amount is
     * {@code ROUND(base_total * pct, 2)}.</p>
     *
     * @param memberId   the member ID
     * @param baseTotal  the base order total before discount
     * @return           the discount amount (not the percentage)
     * @throws MemberNotFoundException if the member does not exist (Java equivalent of the
     *         stored procedure's {@code RAISE EXCEPTION ... ERRCODE = 'P0002'})
     */
    @Transactional
    public BigDecimal calculateDiscount(Long memberId, BigDecimal baseTotal) {
        Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new MemberNotFoundException("Member not found: " + memberId));

        BigDecimal discountPct = discountPctForTier(member.getTier());
        BigDecimal discountAmt = baseTotal.multiply(discountPct).setScale(2, RoundingMode.HALF_UP);

        // Insert audit row (preserves the stored procedure's observable side effect).
        DiscountAudit audit = new DiscountAudit();
        audit.setMemberId(memberId);
        audit.setBaseTotal(baseTotal);
        audit.setDiscountPct(discountPct);
        audit.setDiscountAmt(discountAmt);
        audit.setAppliedAt(LocalDateTime.now());
        discountAuditRepository.save(audit);

        return discountAmt;
    }

    /**
     * Maps a loyalty tier to its discount fraction, mirroring the stored procedure's
     * {@code CASE} expression (unknown/null tiers fall through to the BRONZE default of 0.02).
     *
     * @param tier the member tier string
     * @return the discount fraction (0.02, 0.05, 0.08, or 0.12)
     */
    private BigDecimal discountPctForTier(String tier) {
        if (tier == null) {
            return new BigDecimal("0.02");
        }
        switch (tier) {
            case "PLATINUM":
                return new BigDecimal("0.12");
            case "GOLD":
                return new BigDecimal("0.08");
            case "SILVER":
                return new BigDecimal("0.05");
            default:
                return new BigDecimal("0.02"); // BRONZE default
        }
    }

    /**
     * Calculates the discounted line total for a member purchasing a product. Prices the line via
     * the shared {@link PricingService} then applies the member discount (which also writes an
     * audit row).
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
     * @param memberId  the member ID
     * @return          the tier string (BRONZE, SILVER, GOLD, PLATINUM)
     * @throws MemberNotFoundException if the member does not exist
     */
    public String getMemberTier(Long memberId) {
        return memberRepository.findById(memberId)
            .map(Member::getTier)
            .orElseThrow(() -> new MemberNotFoundException("Member not found: " + memberId));
    }
}
