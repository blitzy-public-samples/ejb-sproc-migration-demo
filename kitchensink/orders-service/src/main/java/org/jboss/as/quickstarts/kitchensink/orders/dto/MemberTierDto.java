package org.jboss.as.quickstarts.kitchensink.orders.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.Serializable;

/**
 * Consumer DTO for Contract 2 (Tier): orders-service reads the users-service
 * response GET {users.base-url}/api/members/{memberId}/tier which returns
 * {"tier":"GOLD"} (one of BRONZE, SILVER, GOLD, PLATINUM).
 *
 * <p>Plain HTTP-contract carrier — no JPA, no business logic. Intentionally
 * separate from the users-service producer DTO (MemberTierResponse): no shared
 * module, per the cross-domain boundary rule (AAP &sect;0.3.3, &sect;0.6.2).
 * Tolerant of unknown properties so a producer-side additive change cannot
 * break the read.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemberTierDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tier;

    public MemberTierDto() {
    }

    public MemberTierDto(String tier) {
        this.tier = tier;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }
}
