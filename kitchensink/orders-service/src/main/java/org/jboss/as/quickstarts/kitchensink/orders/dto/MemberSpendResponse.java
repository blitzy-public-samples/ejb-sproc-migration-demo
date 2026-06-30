package org.jboss.as.quickstarts.kitchensink.orders.dto;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Producer DTO for Contract 3 (Spend): orders-service exposes
 * GET /internal/members/{memberId}/spend?days= returning {"totalSpend":2750.00},
 * the member's rolling N-day sum of CONFIRMED order totals.
 *
 * <p>Plain HTTP-contract carrier — no JPA, no business logic. Intentionally
 * separate from the users-service consumer DTO (MemberSpendDto): no shared
 * module, per the cross-domain boundary rule (AAP &sect;0.3.3, &sect;0.6.2).</p>
 */
public class MemberSpendResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private BigDecimal totalSpend;

    public MemberSpendResponse() {
    }

    public MemberSpendResponse(BigDecimal totalSpend) {
        this.totalSpend = totalSpend;
    }

    public BigDecimal getTotalSpend() {
        return totalSpend;
    }

    public void setTotalSpend(BigDecimal totalSpend) {
        this.totalSpend = totalSpend;
    }
}
