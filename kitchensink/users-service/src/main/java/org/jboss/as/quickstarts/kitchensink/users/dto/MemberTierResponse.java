package org.jboss.as.quickstarts.kitchensink.users.dto;

import java.io.Serializable;

/**
 * Producer DTO for Contract 2 (Tier): users-service exposes
 * GET /api/members/{memberId}/tier returning {"tier":"GOLD"}.
 * The tier value is one of BRONZE, SILVER, GOLD, PLATINUM.
 *
 * Plain HTTP-contract carrier — no JPA, no business logic. Intentionally
 * separate from the orders-service consumer DTO (MemberTierDto): no shared
 * module, per the cross-domain boundary rule.
 */
public class MemberTierResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tier;

    public MemberTierResponse() {
    }

    public MemberTierResponse(String tier) {
        this.tier = tier;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }
}
