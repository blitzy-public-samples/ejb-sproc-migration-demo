package org.jboss.as.quickstarts.kitchensink.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.data.OrderRepository;
import org.jboss.as.quickstarts.kitchensink.model.Member;
import org.jboss.as.quickstarts.kitchensink.model.Order;
import org.jboss.as.quickstarts.kitchensink.service.TierRecalculationService;

/**
 * Integration tests for {@link TierRecalculationService} (the Java re-implementation of
 * {@code recalculate_customer_tiers}).
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): rewritten from Arquillian/JUnit 4 to
 * {@code @SpringBootTest} + JUnit 5. The class is {@code @Transactional} so the test members and orders it
 * creates — and any tier changes the recalculation makes to the externally-seeded members — are rolled
 * back at the end of each test. This both isolates the tests and protects the seed data (the recalculation
 * loops over <em>all</em> members), replacing the legacy manual {@code UserTransaction} cleanup. Each test
 * asserts only on the tier of the member it created.</p>
 *
 * <p>Tier thresholds (90-day rolling CONFIRMED spend): PLATINUM ≥ $5,000, GOLD ≥ $2,000, SILVER ≥ $500,
 * else BRONZE.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TierRecalculationIT {

    @Autowired
    private TierRecalculationService tierRecalculationService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private OrderRepository orderRepository;

    /** Creates and persists a new test member with a unique email. */
    private Member createTestMember(String tier, BigDecimal totalSpend) {
        Member m = new Member();
        m.setName("Test Member");
        m.setEmail("test-" + UUID.randomUUID() + "@test.com");
        m.setPhoneNumber("9195559999");
        m.setTier(tier);
        m.setTotalSpend(totalSpend);
        return memberRepository.save(m);
    }

    /** Creates and persists a CONFIRMED order for a member with a given total and timestamp. */
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
     * Test 1: a new member with no orders remains BRONZE after recalculation.
     */
    @Test
    void testNewMemberRemainsBronze() {
        Member member = createTestMember("BRONZE", BigDecimal.ZERO);

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        assertEquals("BRONZE", updated.getTier(),
            "New member with no orders should remain BRONZE");
    }

    /**
     * Test 2: a member with $750 of CONFIRMED spend within the window is upgraded to SILVER.
     */
    @Test
    void testMemberUpgradesToSilverAfterSpend() {
        Member member = createTestMember("BRONZE", BigDecimal.ZERO);
        createTestOrder(member.getId(), new BigDecimal("750.00"), LocalDateTime.now().minusDays(15));

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        assertEquals("SILVER", updated.getTier(),
            "Member with $750 spend in 15 days should be upgraded to SILVER");
    }

    /**
     * Test 3: a member with $3,000 of CONFIRMED spend within the window is upgraded to GOLD.
     */
    @Test
    void testMemberUpgradesToGoldAfterSpend() {
        Member member = createTestMember("BRONZE", BigDecimal.ZERO);
        createTestOrder(member.getId(), new BigDecimal("3000.00"), LocalDateTime.now().minusDays(45));

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        assertEquals("GOLD", updated.getTier(),
            "Member with $3,000 spend in 45 days should be upgraded to GOLD");
    }

    /**
     * Test 4: a GOLD member whose only order is older than the 90-day window drops to BRONZE, because
     * the order no longer counts toward the rolling spend.
     */
    @Test
    void testMemberDoesNotDowngradeOnOldOrders() {
        Member member = createTestMember("GOLD", new BigDecimal("3000.00"));
        // $3,000 order placed 92 days ago — outside the 90-day window, so it does not count.
        createTestOrder(member.getId(), new BigDecimal("3000.00"), LocalDateTime.now().minusDays(92));

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        assertEquals("BRONZE", updated.getTier(),
            "GOLD member with all orders > 90 days old should drop to BRONZE (no qualifying spend)");
    }
}
