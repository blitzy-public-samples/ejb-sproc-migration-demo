package org.jboss.as.quickstarts.kitchensink.users.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Consumer DTO for Contract 3 (Spend): users-service reads the orders-service
 * response GET {orders.base-url}/internal/members/{memberId}/spend?days=
 * which returns {"totalSpend":2750.00} (the 90-day rolling spend).
 *
 * Plain HTTP-contract carrier — no JPA, no business logic. Intentionally
 * separate from the orders-service producer DTO (MemberSpendResponse): no
 * shared module, per the cross-domain boundary rule. Tolerant of unknown
 * properties so a producer-side additive change cannot break the read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemberSpendDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private BigDecimal totalSpend;

    public MemberSpendDto() {
    }

    public BigDecimal getTotalSpend() {
        return totalSpend;
    }

    public void setTotalSpend(BigDecimal totalSpend) {
        this.totalSpend = totalSpend;
    }
}
