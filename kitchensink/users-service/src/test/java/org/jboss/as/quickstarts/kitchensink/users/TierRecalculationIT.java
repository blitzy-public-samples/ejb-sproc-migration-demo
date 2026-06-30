package org.jboss.as.quickstarts.kitchensink.users;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.jboss.as.quickstarts.kitchensink.users.model.Member;
import org.jboss.as.quickstarts.kitchensink.users.repository.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.users.service.TierRecalculationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TierRecalculationIT - users-service integration test for the nightly loyalty-tier
 * recalculation, migrated from the legacy Arquillian/JUnit 4 test
 * (kitchensink/src/test/java/org/jboss/as/quickstarts/kitchensink/test/TierRecalculationIT.java)
 * to {@code @SpringBootTest} + Testcontainers PostgreSQL + {@code MockRestServiceServer} + JUnit 5.
 *
 * <p>The test drives the real {@link TierRecalculationService} against a real Testcontainers
 * PostgreSQL database, while stubbing the cross-service spend HTTP call (Contract 3:
 * {@code GET /internal/members/{id}/spend?days=}) with {@code MockRestServiceServer} bound to the
 * shared {@link RestTemplate} bean - the exact instance {@code OrdersClient} uses internally. No
 * real HTTP leaves the JVM and no peer service needs to be running.</p>
 *
 * <p>The legacy {@code EntityManager}/{@code UserTransaction} re-read pattern and the
 * {@code createTestOrder} helper are replaced: orders belong to a different bounded context, so the
 * trailing-90-day spend window (the orders-service producer's responsibility) is simulated by
 * stubbing the post-filter spend value the producer would return. Tier thresholds are preserved
 * EXACTLY: PLATINUM &gt;= 5000, GOLD &gt;= 2000, SILVER &gt;= 500, else BRONZE.</p>
 */
@SpringBootTest
@Testcontainers
class TierRecalculationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /**
     * Loads the authoritative schema then the seed data via direct JDBC before the Spring context
     * starts, so Hibernate {@code ddl-auto=validate} finds the {@code member} table it maps. Only
     * 01_schema.sql and 03_seed_data.sql are loaded - never 02_stored_procedures.sql, because the
     * migrated service no longer calls any stored procedure (zero native queries).
     */
    @BeforeAll
    static void loadSchemaAndSeed() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/01_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/03_seed_data.sql"));
        }
    }

    @Autowired
    private TierRecalculationService tierRecalculationService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RestTemplate restTemplate;

    private MockRestServiceServer mockServer;

    /**
     * FK-safe clean slate: 03_seed_data.sql inserts only members 1-3 and no orders/audit rows, so
     * {@code deleteAll()} cannot violate any foreign key. Removing the seeded members guarantees
     * {@code findAll()} during recalculation returns ONLY the test-created member, so every
     * {@code getMemberSpend} call has a matching stub. The mock server is rebuilt per test so each
     * scenario has fresh, isolated stubs bound to the shared RestTemplate bean.
     */
    @BeforeEach
    void setUp() {
        memberRepository.deleteAll();
        mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
    }

    /**
     * Helper: creates and persists a new test member with a unique email (preserving the legacy
     * name/email/phone values exactly).
     */
    private Member createTestMember(String tier, BigDecimal totalSpend) {
        Member member = new Member();
        member.setName("Test Member");
        member.setEmail("test-" + UUID.randomUUID() + "@test.com");
        member.setPhoneNumber("9195559999");
        member.setTier(tier);
        member.setTotalSpend(totalSpend);
        return memberRepository.save(member);
    }

    /**
     * Helper: stubs Contract 3 (member spend). Matches {@code GET .../internal/members/{id}/spend}
     * and responds with {@code {"totalSpend":N}} as JSON, simulating the post-90-day-filter spend
     * the orders-service producer would return.
     */
    private void stubSpend(Long memberId, BigDecimal spend) {
        mockServer.expect(ExpectedCount.manyTimes(),
                        requestTo(containsString("/internal/members/" + memberId + "/spend")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"totalSpend\":" + spend.toPlainString() + "}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void testNewMemberRemainsBronze() {
        Member member = createTestMember("BRONZE", BigDecimal.ZERO);
        stubSpend(member.getId(), BigDecimal.ZERO);

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        Assertions.assertEquals("BRONZE", updated.getTier(),
                "New member with no orders should remain BRONZE");
    }

    @Test
    void testMemberUpgradesToSilverAfterSpend() {
        Member member = createTestMember("BRONZE", BigDecimal.ZERO);
        stubSpend(member.getId(), new BigDecimal("750.00"));

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        Assertions.assertEquals("SILVER", updated.getTier(),
                "Member with $750 spend in 30 days should be upgraded to SILVER");
    }

    @Test
    void testMemberUpgradesToGoldAfterSpend() {
        Member member = createTestMember("BRONZE", BigDecimal.ZERO);
        stubSpend(member.getId(), new BigDecimal("3000.00"));

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        Assertions.assertEquals("GOLD", updated.getTier(),
                "Member with $3,000 spend in 45 days should be upgraded to GOLD");
    }

    @Test
    void testMemberDoesNotDowngradeOnOldOrders() {
        Member member = createTestMember("GOLD", new BigDecimal("3000.00"));
        stubSpend(member.getId(), BigDecimal.ZERO);

        tierRecalculationService.triggerRecalculation();

        Member updated = memberRepository.findById(member.getId()).orElseThrow();
        Assertions.assertEquals("BRONZE", updated.getTier(),
                "GOLD member with all orders > 91 days old should drop to BRONZE (no qualifying 90-day spend)");
    }
}
