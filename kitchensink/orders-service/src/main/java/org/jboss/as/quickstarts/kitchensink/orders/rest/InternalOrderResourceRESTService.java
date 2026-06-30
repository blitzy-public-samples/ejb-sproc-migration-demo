package org.jboss.as.quickstarts.kitchensink.orders.rest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.jboss.as.quickstarts.kitchensink.orders.dto.MemberSpendResponse;
import org.jboss.as.quickstarts.kitchensink.orders.repository.OrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        BigDecimal totalSpend = orderRepository.sumConfirmedTotalSince(memberId, since);
        if (totalSpend == null) {
            totalSpend = BigDecimal.ZERO;
        }
        return ResponseEntity.ok(new MemberSpendResponse(totalSpend));
    }
}
