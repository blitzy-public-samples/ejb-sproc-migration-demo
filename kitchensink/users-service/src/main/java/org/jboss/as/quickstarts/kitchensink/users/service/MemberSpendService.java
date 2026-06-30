package org.jboss.as.quickstarts.kitchensink.users.service;

import java.math.BigDecimal;

import org.jboss.as.quickstarts.kitchensink.users.model.Member;
import org.jboss.as.quickstarts.kitchensink.users.repository.MemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owns the lifetime-spend write side of the {@code member} aggregate (users-service).
 *
 * <p>Realizes the Source-A {@code process_order} side-effect: when orders-service confirms an order
 * it increments the member's lifetime {@code total_spend} by the order SUBTOTAL (AAP &sect;0.6.1 /
 * &sect;0.7.3 — Source A overrides the Source-B SQL which added the total). The {@code orders} domain
 * cannot touch the {@code member} table directly (boundary rule, AAP &sect;0.7.2), so it invokes this
 * service over HTTP via the internal write contract ({@code POST /internal/members/{id}/total-spend}).</p>
 *
 * <p>The increment mutates ONLY {@code total_spend}; the loyalty {@code tier} and
 * {@code tier_updated_at} fields are deliberately left untouched, because tier is driven exclusively
 * by the 90-day ROLLING spend computed nightly by {@code TierRecalculationService} — not by the
 * lifetime running total (AAP &sect;0.6.5).</p>
 */
@Service
public class MemberSpendService {

    private final MemberRepository memberRepository;

    public MemberSpendService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /**
     * Atomically adds {@code amount} to the member's lifetime {@code total_spend}.
     *
     * <p>Runs in its own transaction so the read-modify-write of {@code total_spend} commits (or rolls
     * back) as a unit. Only {@code total_spend} is changed (see class javadoc); {@code tier} and
     * {@code tier_updated_at} are preserved.</p>
     *
     * @param memberId the id of the member whose lifetime spend is incremented
     * @param amount   the positive amount (order subtotal) to add
     * @throws ResponseStatusException 400 BAD_REQUEST when {@code amount} is null or not positive;
     *                                 404 NOT_FOUND when no member exists for {@code memberId}
     */
    @Transactional
    public void incrementTotalSpend(Long memberId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "amount must be a positive value");
        }
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Member not found: " + memberId));

        BigDecimal current = (member.getTotalSpend() != null)
                ? member.getTotalSpend() : BigDecimal.ZERO;
        member.setTotalSpend(current.add(amount));
        memberRepository.save(member);
    }
}
