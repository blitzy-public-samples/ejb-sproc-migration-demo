package org.jboss.as.quickstarts.kitchensink.users;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
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

    /**
     * Helper: stubs Contract 3 for a member with a 500 response, so {@code OrdersClient.getMemberSpend}
     * maps it to a {@code ServiceUnavailableException} (a {@code RuntimeException}). Used to verify the
     * scheduled run isolates a single member's downstream failure and keeps processing the others.
     */
    private void stubSpendServerError(Long memberId) {
        mockServer.expect(ExpectedCount.manyTimes(),
                        requestTo(containsString("/internal/members/" + memberId + "/spend")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());
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

    /**
     * Persist-only-on-change (checkpoint coverage gap). When the recomputed tier equals the member's
     * current tier, the service must NOT write the row: both {@code tier} and {@code tier_updated_at}
     * stay exactly as they were (the SQL {@code WHERE tier IS DISTINCT FROM ...} guard).
     *
     * <p>The member starts at SILVER with a fixed, known {@code tier_updated_at}. The stubbed 90-day
     * spend ($750) recomputes to SILVER -- unchanged -- so no {@code save} occurs and the stamped
     * timestamp is preserved. The before/after timestamps are both read back from the database so the
     * equality check is exact (no in-memory vs. JDBC precision mismatch).</p>
     */
    @Test
    void testNoChangeLeavesTierAndTimestampUnchanged() {
        // Member already at the tier its spend maps to, with a known, clearly-old tier_updated_at.
        Member member = createTestMember("SILVER", new BigDecimal("750.00"));
        member.setTierUpdatedAt(LocalDateTime.of(2020, 1, 1, 0, 0, 0));
        memberRepository.save(member);

        // Read the DB-stored timestamp so the post-run comparison is precision-exact.
        Member before = memberRepository.findById(member.getId()).orElseThrow();
        LocalDateTime tsBefore = before.getTierUpdatedAt();
        Assertions.assertNotNull(tsBefore, "precondition: tier_updated_at should be stamped");

        // $750 -> computeTier -> SILVER, which equals the current tier: nothing should be persisted.
        stubSpend(member.getId(), new BigDecimal("750.00"));
        tierRecalculationService.triggerRecalculation();

        Member after = memberRepository.findById(member.getId()).orElseThrow();
        Assertions.assertEquals("SILVER", after.getTier(),
                "Tier must remain SILVER when the recomputed tier equals the current tier");
        Assertions.assertEquals(tsBefore, after.getTierUpdatedAt(),
                "tier_updated_at must NOT change when the tier is unchanged (persist-only-on-change)");
    }

    /**
     * Per-member failure isolation (F6 / reliability coverage gap). A single member's spend-lookup
     * failure must NOT abort the whole recalculation run: every other member is still processed.
     *
     * <p>Two BRONZE members are created. The healthy member's spend stubs to $3,000 (-> GOLD); the
     * other member's spend endpoint returns 500, which {@code OrdersClient} translates to a
     * {@code ServiceUnavailableException}. After the run, the healthy member is upgraded to GOLD
     * (proving the loop continued past the failure) and the failed member is left unchanged at
     * BRONZE (proving the failure produced no partial/incorrect write).</p>
     */
    @Test
    void testRecalculationIsolatesPerMemberDownstreamFailure() {
        Member memberOk = createTestMember("BRONZE", BigDecimal.ZERO);
        Member memberFail = createTestMember("BRONZE", BigDecimal.ZERO);

        stubSpend(memberOk.getId(), new BigDecimal("3000.00")); // -> GOLD
        stubSpendServerError(memberFail.getId());               // -> ServiceUnavailableException

        // Must not throw: the per-member try/catch isolates the failing member.
        tierRecalculationService.triggerRecalculation();

        Member okAfter = memberRepository.findById(memberOk.getId()).orElseThrow();
        Member failAfter = memberRepository.findById(memberFail.getId()).orElseThrow();

        Assertions.assertEquals("GOLD", okAfter.getTier(),
                "Healthy member must still be recalculated when another member's spend lookup fails");
        Assertions.assertEquals("BRONZE", failAfter.getTier(),
                "Member whose spend lookup failed must be left unchanged (no partial update)");
    }
}
