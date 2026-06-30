package org.jboss.as.quickstarts.kitchensink.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import org.jboss.as.quickstarts.kitchensink.users.client.OrdersClient;
import org.jboss.as.quickstarts.kitchensink.users.model.Member;
import org.jboss.as.quickstarts.kitchensink.users.repository.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.users.service.TierRecalculationService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link TierRecalculationService}, the pure-Java reimplementation of the
 * {@code recalculate_customer_tiers()} stored procedure.
 *
 * <p>Faithful rewrite of the monolith's Arquillian {@code TierRecalculationIT} into the
 * {@code @SpringBootTest} + Testcontainers (JUnit 5) pattern. Each member's 90-day ROLLING spend is
 * supplied by stubbing the {@link OrdersClient} gateway (Contract 3) — the {@code orders} table is
 * owned by orders-service and is never persisted here (boundary rule, AAP &sect;0.7.2). This proves
 * the lifetime-vs-rolling distinction (AAP &sect;0.6.5): a member's lifetime {@code totalSpend} does
 * not drive the tier; only the 90-day rolling spend does.</p>
 *
 * <p>The class is intentionally NOT {@code @Transactional}: the production
 * {@code @Transactional triggerRecalculation()} must run in its own transaction and see the
 * committed test member rows.</p>
 *
 * <p>Tier thresholds: &ge;5000 PLATINUM, &ge;2000 GOLD, &ge;500 SILVER, else BRONZE.</p>
 */
@SpringBootTest
@Testcontainers
class TierRecalculationIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("kitchensink");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /**
     * Seed the container from the three authoritative scripts IN ORDER, each executed as a SINGLE
     * statement (NEVER split on ';') because {@code 02_stored_procedures.sql} has dollar-quoted ($$)
     * PL/pgSQL bodies. Module-relative {@code ../db/...} resolves with CWD = {@code users-service/}.
     */
    @BeforeAll
    static void seedDatabase() throws Exception {
        String[] scripts = {
            "../db/01_schema.sql",
            "../db/02_stored_procedures.sql",
            "../db/03_seed_data.sql"
        };
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            for (String script : scripts) {
                String sql = Files.readString(Path.of(script), StandardCharsets.UTF_8);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
        }
    }

    @Autowired
    private TierRecalculationService tierRecalculationService;

    @Autowired
    private MemberRepository memberRepository;

    @MockBean
    private OrdersClient ordersClient;

    /**
     * Persist a fresh, schema-valid member (committed immediately because this class is not
     * {@code @Transactional}). Name &le; 25 chars and digit-free; phone 10 digits; email unique.
     */
    private Member persistMember(String tier, BigDecimal totalSpend) {
        Member member = new Member();
        member.setName("Test Member");
        member.setEmail("tier-" + UUID.randomUUID() + "@test.com");
        member.setPhoneNumber("9195559999");
        member.setTier(tier);
        member.setTotalSpend(totalSpend);
        return memberRepository.save(member);
    }

    /** Scenario 1: new BRONZE member, zero rolling spend -> stays BRONZE. */
    @Test
    void testNewMemberRemainsBronze() {
        Member member = persistMember("BRONZE", BigDecimal.ZERO);
        when(ordersClient.getNinetyDaySpend(member.getId())).thenReturn(BigDecimal.ZERO);

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        assertEquals("BRONZE", updated.getTier(),
                "New member with zero 90-day spend should remain BRONZE");
    }

    /** Scenario 2: rolling spend 750.00 (500..1999.99) -> SILVER. */
    @Test
    void testMemberUpgradesToSilver() {
        Member member = persistMember("BRONZE", BigDecimal.ZERO);
        when(ordersClient.getNinetyDaySpend(member.getId())).thenReturn(new BigDecimal("750.00"));

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        assertEquals("SILVER", updated.getTier(),
                "Member with $750 90-day rolling spend should be upgraded to SILVER");
    }

    /** Scenario 3: rolling spend 3000.00 (2000..4999.99) -> GOLD. */
    @Test
    void testMemberUpgradesToGold() {
        Member member = persistMember("BRONZE", BigDecimal.ZERO);
        when(ordersClient.getNinetyDaySpend(member.getId())).thenReturn(new BigDecimal("3000.00"));

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        assertEquals("GOLD", updated.getTier(),
                "Member with $3000 90-day rolling spend should be upgraded to GOLD");
    }

    /**
     * Scenario 4 (lifetime-vs-rolling proof): member starts GOLD with a high lifetime totalSpend,
     * but the 90-day ROLLING spend is zero -> drops to BRONZE. The lifetime total is irrelevant.
     */
    @Test
    void testGoldMemberDropsToBronzeWhenNoRollingSpend() {
        Member member = persistMember("GOLD", new BigDecimal("3000.00"));
        when(ordersClient.getNinetyDaySpend(member.getId())).thenReturn(BigDecimal.ZERO);

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        assertEquals("BRONZE", updated.getTier(),
                "GOLD member with zero 90-day rolling spend should drop to BRONZE "
                        + "(lifetime totalSpend must not drive the tier)");
    }
}
