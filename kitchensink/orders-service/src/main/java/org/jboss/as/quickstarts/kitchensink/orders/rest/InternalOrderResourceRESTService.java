package org.jboss.as.quickstarts.kitchensink.orders.rest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.jboss.as.quickstarts.kitchensink.orders.dto.MemberSpendResponse;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal cross-service API for orders-service.
 *
 * <p>Contract 3 (Spend) PRODUCER: users-service calls this during nightly tier
 * recalculation to obtain a member's rolling N-day CONFIRMED spend.
 *
 * <p>Served under context-path {@code /orders}, so the full external path is
 * {@code GET /orders/internal/members/{memberId}/spend?days=90}.
 */
@RestController
@RequestMapping("/internal")
public class InternalOrderResourceRESTService {

    /**
     * Upper bound (10 years) for the {@code days} spend window (review MED2, CWE-20). Bounds the
     * rolling window so a caller cannot request an unboundedly large range that would force a full
     * scan of the orders history; combined with the lower bound of {@code 1}, only a sensible
     * positive window is accepted.
     */
    private static final int MAX_SPEND_WINDOW_DAYS = 3650;

    private final OrderRepository orderRepository;

    public InternalOrderResourceRESTService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * GET /internal/members/{memberId}/spend?days=90
     *
     * <p>Returns the SUM of the member's CONFIRMED order totals within the last
     * {@code days} days as {@code {"totalSpend": N}}. COALESCE(...,0) in the
     * repository query means an unknown member, or one with no qualifying orders,
     * yields {@code 200 {"totalSpend":0}} (the users-service consumer treats both
     * 404 and 0 as a zero spend).
     */
    @GetMapping("/members/{memberId}/spend")
    public ResponseEntity<MemberSpendResponse> getMemberSpend(
            @PathVariable("memberId") Long memberId,
            @RequestParam(name = "days", defaultValue = "90") int days) {
        // Validate the window bounds (review MED2): reject zero/negative and absurdly large ranges with
        // a controlled 400 rather than silently accepting them (a negative window would compute a
        // future "since" and always return 0; an unbounded window forces a full-history scan).
        if (days < 1 || days > MAX_SPEND_WINDOW_DAYS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "days must be between 1 and " + MAX_SPEND_WINDOW_DAYS);
        }
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        BigDecimal totalSpend = orderRepository.sumConfirmedTotalSince(memberId, since);
        if (totalSpend == null) {
            totalSpend = BigDecimal.ZERO;
        }
        return ResponseEntity.ok(new MemberSpendResponse(totalSpend));
    }
}
