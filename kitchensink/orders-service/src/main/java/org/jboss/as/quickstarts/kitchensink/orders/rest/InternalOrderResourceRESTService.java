package org.jboss.as.quickstarts.kitchensink.orders.rest;

import java.math.BigDecimal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.jboss.as.quickstarts.kitchensink.orders.dto.MemberSpendResponse;
import org.jboss.as.quickstarts.kitchensink.orders.service.OrderService;

/**
 * Internal (service-to-service) REST edge for orders-service. Exposes the Contract 3 (Spend)
 * producer at /internal/members/{memberId}/spend (combined with context-path /orders, the external
 * path is /orders/internal/members/{id}/spend -- intentionally NOT under /api). Consumed by
 * users-service's OrdersClient (Contract 3, AAP 0.6.2).
 */
@RestController
@RequestMapping("/internal/members")
public class InternalOrderResourceRESTService {

    private final OrderService orderService;

    // Constructor injection (single constructor -> no @Autowired required).
    public InternalOrderResourceRESTService(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * GET /internal/members/{memberId}/spend?days={days} (Contract 3 producer).
     * Returns the member's total spend over CONFIRMED orders in the last {days} days as
     * {"totalSpend":...}. The producer always returns the computed sum (0 when there are no
     * qualifying orders); the consumer (users-service) maps 404 -> BigDecimal.ZERO on its side.
     *
     * <p>{@code days} is bounded to [1, 365]: a non-positive value yields a meaningless/empty window
     * and an excessive value triggers an expensive unbounded read. Out-of-range values are rejected
     * with HTTP 400. The constraint annotations on the parameter are enforced by Spring's built-in
     * controller method validation (Spring Framework 6.1+, no {@code @Validated} required), which
     * raises {@code HandlerMethodValidationException} -> 400.</p>
     */
    @GetMapping("/{memberId}/spend")
    public MemberSpendResponse getMemberSpend(
            @PathVariable Long memberId,
            @RequestParam(name = "days") @Min(1) @Max(365) int days) {
        BigDecimal totalSpend = orderService.computeMemberSpend(memberId, days);
        return new MemberSpendResponse(totalSpend);
    }
}
