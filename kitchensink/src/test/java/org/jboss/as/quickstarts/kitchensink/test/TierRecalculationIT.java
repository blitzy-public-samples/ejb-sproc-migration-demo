package org.jboss.as.quickstarts.kitchensink.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.data.OrderRepository;
import org.jboss.as.quickstarts.kitchensink.model.Member;
import org.jboss.as.quickstarts.kitchensink.model.Order;
import org.jboss.as.quickstarts.kitchensink.service.TierRecalculationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link TierRecalculationService} (migrated from Arquillian/JUnit 4 to
 * Spring Boot @SpringBootTest / JUnit 5). Class-level @Transactional (rollback) protects the
 * shared seed members: triggerRecalculation() recomputes ALL members, so the test transaction
 * is rolled back to restore seed tiers for the other ITs.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class TierRecalculationIT {

    @Autowired
    private TierRecalculationService tierRecalculationService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private OrderRepository orderRepository;

    /**
     * Helper: creates and persists a new test member with a unique email.
     */
    private Member createTestMember(String tier, BigDecimal totalSpend) {
        Member m = new Member();
        m.setName("Test Member");
        m.setEmail("test-" + UUID.randomUUID() + "@test.com");
        m.setPhoneNumber("9195559999");
        m.setTier(tier);
        m.setTotalSpend(totalSpend);
        return memberRepository.save(m);
    }

    /**
     * Helper: creates and persists a CONFIRMED order for a member with a given total and timestamp.
     */
    private Order createTestOrder(Long memberId, BigDecimal total, LocalDateTime createdAt) {
        Order o = new Order();
        o.setMemberId(memberId);
        o.setStatus("CONFIRMED");
        o.setSubtotal(total);
        o.setDiscountAmount(BigDecimal.ZERO);
        o.setShippingCost(BigDecimal.ZERO);
        o.setTotal(total);
        o.setCreatedAt(createdAt);
        return orderRepository.save(o);
    }

    /**
     * Test 1: A new member with no orders should remain BRONZE after recalculation.
     */
    @Test
    public void testNewMemberRemainsBronze() {
        Member member = createTestMember("BRONZE", BigDecimal.ZERO);

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        assertEquals("BRONZE", updated.getTier(),
            "New member with no orders should remain BRONZE");
    }

    /**
     * Test 2: Member with $750 in CONFIRMED orders within 30 days should become SILVER.
     */
    @Test
    public void testMemberUpgradesToSilverAfterSpend() {
        Member member = createTestMember("BRONZE", BigDecimal.ZERO);
        createTestOrder(member.getId(), new BigDecimal("750.00"), LocalDateTime.now().minusDays(15));

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        assertEquals("SILVER", updated.getTier(),
            "Member with $750 spend in 30 days should be upgraded to SILVER");
    }

    /**
     * Test 3: Member with $3,000 in CONFIRMED orders within 90 days should become GOLD.
     */
    @Test
    public void testMemberUpgradesToGoldAfterSpend() {
        Member member = createTestMember("BRONZE", BigDecimal.ZERO);
        createTestOrder(member.getId(), new BigDecimal("3000.00"), LocalDateTime.now().minusDays(45));

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        assertEquals("GOLD", updated.getTier(),
            "Member with $3,000 spend in 45 days should be upgraded to GOLD");
    }

    /**
     * Test 4: A GOLD member whose orders are all older than 91 days should drop to BRONZE.
     */
    @Test
    public void testMemberDoesNotDowngradeOnOldOrders() {
        Member member = createTestMember("GOLD", new BigDecimal("3000.00"));
        // Place $3,000 order but 92 days ago — outside the 90-day window
        createTestOrder(member.getId(), new BigDecimal("3000.00"), LocalDateTime.now().minusDays(92));

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        assertEquals("BRONZE", updated.getTier(),
            "GOLD member with all orders > 91 days old should drop to BRONZE (no qualifying 90-day spend)");
    }

    /**
     * Test 5: the application-readiness lifecycle hook ({@code @EventListener(ApplicationReadyEvent)},
     * the Spring replacement for the legacy EJB {@code @Startup}) must perform a real tier
     * recalculation — not merely log readiness. A member carrying a stale GOLD tier but with no
     * qualifying 90-day CONFIRMED spend must be corrected to BRONZE when the readiness handler runs.
     *
     * <p>This invokes {@link TierRecalculationService#onApplicationReady()} directly (the same method
     * Spring invokes when {@code ApplicationReadyEvent} is published at startup) and asserts the tier
     * is recomputed, guarding against a regression to a no-op startup handler.</p>
     */
    @Test
    public void testApplicationReadyEventRecalculatesTiers() {
        Member member = createTestMember("GOLD", new BigDecimal("3000.00"));

        tierRecalculationService.onApplicationReady();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        assertEquals("BRONZE", updated.getTier(),
            "ApplicationReadyEvent handler must recalculate tiers: a member with no qualifying 90-day "
                + "CONFIRMED spend drops to BRONZE at application readiness");
    }
}
