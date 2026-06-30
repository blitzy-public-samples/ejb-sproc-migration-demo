package org.jboss.as.quickstarts.kitchensink.users.service;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.jboss.as.quickstarts.kitchensink.users.exception.MemberNotFoundException;
import org.jboss.as.quickstarts.kitchensink.users.model.Member;
import org.jboss.as.quickstarts.kitchensink.users.repository.MemberRepository;

/**
 * Applies the GAP-3 cross-domain member spend increment (AAP &sect;0.6.6) IDEMPOTENTLY.
 *
 * <p>orders-service calls {@code POST /api/members/{id}/spend} after each order commits to add the
 * order total to the member's {@code total_spend} (users-service owns the {@code member} table, so
 * orders-service never writes it directly). Because that call is post-commit and crosses an HTTP
 * boundary, it can be retried or duplicated. This service deduplicates by the originating
 * {@code orderId} (the orders table's globally-unique primary key) so each order's spend is applied
 * AT MOST ONCE.</p>
 *
 * <p><b>Why the idempotency ledger is in-memory.</b> The database schema is frozen
 * (AAP &sect;0.2.2 -- {@code db/01_schema.sql} must not change), so a durable {@code processed_orders}
 * table or an idempotency column on {@code member} cannot be added. The dedupe set is therefore a
 * process-local, thread-safe {@link Set} ({@link ConcurrentHashMap#newKeySet()}). This guarantees
 * idempotency for the dominant case (retries/duplicates within a running instance). Consistent with
 * AAP &sect;0.6.6, {@code total_spend} is treated as an eventually-consistent cached value: the
 * authoritative, durable source of truth for spend is the CONFIRMED-order history in orders-service,
 * which the nightly tier recalculation reads (via {@code OrdersClient.getMemberSpend}) to recompute
 * each member's loyalty tier independently of this cached column.</p>
 */
@Service
public class MemberSpendService {

    private static final Logger log = LoggerFactory.getLogger(MemberSpendService.class);

    private final MemberRepository memberRepository;

    /**
     * Process-local idempotency ledger: order ids whose spend has already been applied. Thread-safe
     * for concurrent post-commit increments. See the class Javadoc for why this is in-memory.
     */
    private final Set<Long> processedOrderIds = ConcurrentHashMap.newKeySet();

    // Constructor injection (single constructor -> no @Autowired required).
    public MemberSpendService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /**
     * Idempotently adds {@code amount} to the member's {@code total_spend}, keyed by {@code orderId}.
     *
     * <ul>
     *   <li>Unknown member -&gt; {@link MemberNotFoundException} (HTTP 404).</li>
     *   <li>An {@code orderId} already applied -&gt; no-op (the increment is skipped; the call still
     *       succeeds, so a duplicate post-commit retry is harmless).</li>
     *   <li>Otherwise the increment is applied within this method's local {@code @Transactional}
     *       boundary.</li>
     * </ul>
     *
     * <p>If the database write fails, the in-memory idempotency claim for {@code orderId} is released
     * so that a subsequent retry can re-apply the increment (the claim must not outlive a rolled-back
     * transaction).</p>
     *
     * @param memberId the member whose lifetime spend is incremented
     * @param orderId  the originating order id; idempotency key
     * @param amount   the validated, strictly-positive amount to add
     */
    @Transactional
    public void applySpendIncrement(Long memberId, Long orderId, BigDecimal amount) {
        // Atomically claim this orderId. add() returns false if it was already present -> already
        // applied, so this is an idempotent replay and we skip the mutation.
        if (!processedOrderIds.add(orderId)) {
            log.debug("Spend increment for orderId={} already applied; skipping (idempotent replay)", orderId);
            return;
        }
        try {
            Member member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new MemberNotFoundException("Member not found: " + memberId));
            BigDecimal current = (member.getTotalSpend() != null) ? member.getTotalSpend() : BigDecimal.ZERO;
            member.setTotalSpend(current.add(amount));
            memberRepository.save(member);
            log.debug("Applied spend increment for orderId={} to memberId={}", orderId, memberId);
        } catch (RuntimeException e) {
            // The write did not commit (e.g. unknown member, or a transient persistence failure):
            // release the claim so a legitimate retry of this order can succeed later.
            processedOrderIds.remove(orderId);
            throw e;
        }
    }
}
