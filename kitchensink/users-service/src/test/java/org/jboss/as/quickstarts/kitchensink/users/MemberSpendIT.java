package org.jboss.as.quickstarts.kitchensink.users;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.jboss.as.quickstarts.kitchensink.users.config.InternalServiceAuthInterceptor;
import org.jboss.as.quickstarts.kitchensink.users.model.Member;
import org.jboss.as.quickstarts.kitchensink.users.repository.MemberRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Web-edge integration test for the GAP-3 spend-mutation endpoint
 * {@code POST /api/members/{id}/spend}, driven through {@link MockMvc} so the full Spring MVC chain
 * -- {@link InternalServiceAuthInterceptor}, {@code @Valid} body validation, and
 * {@code MemberSpendService} -- is exercised end-to-end against a real Testcontainers PostgreSQL.
 *
 * <p>Covers the CRITICAL/MAJOR findings on this endpoint:</p>
 * <ul>
 *   <li><b>Access control (CRITICAL).</b> A request WITHOUT a valid
 *       {@value InternalServiceAuthInterceptor#INTERNAL_TOKEN_HEADER} token is rejected with 401;
 *       a request WITH the token is served.</li>
 *   <li><b>Idempotency (MAJOR / GAP-3).</b> Two posts carrying the SAME {@code orderId} apply the
 *       increment exactly once -- {@code total_spend} rises by the amount only on the first call.</li>
 *   <li><b>Input validation (MAJOR).</b> A missing/negative {@code amount} or a missing
 *       {@code orderId} is rejected with 400 before any mutation.</li>
 * </ul>
 *
 * <p>NOTE: {@code MemberSpendService}'s in-memory idempotency ledger is a singleton shared across all
 * methods in this class, so every test uses a DISTINCT {@code orderId} to avoid cross-test
 * interference. MockMvc dispatches at the servlet level, so the {@code /users} context-path is not
 * part of the request URI and the interceptor's {@code /api/members/*}{@code /spend} pattern matches.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public class MemberSpendIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // security.internal.token has no default in application.properties (CWE-798 fix); this test
        // both needs it for context startup AND reads it via @Value to drive the spend-endpoint token
        // guard (401 without / 200 with). High rate limit is harmless here (no registration POSTs).
        registry.add("security.internal.token", () -> "test-internal-token");
        registry.add("security.registration.max-per-minute", () -> "1000000");
    }

    @BeforeAll
    static void loadSchemaAndSeed() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/01_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/03_seed_data.sql"));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Value("${security.internal.token}")
    private String internalToken;

    /** Persists a fresh member (unique email) with total_spend = 0 and returns it. */
    private Member newMember() {
        Member member = new Member();
        member.setName("Spend Test");
        member.setEmail("spend-" + UUID.randomUUID() + "@test.com");
        member.setPhoneNumber("9195551234");
        member.setTier("BRONZE");
        member.setTotalSpend(BigDecimal.ZERO);
        return memberRepository.save(member);
    }

    private BigDecimal totalSpendOf(Long memberId) {
        return memberRepository.findById(memberId).orElseThrow().getTotalSpend();
    }

    /** CRITICAL: no token -> 401, and the member's spend is NOT changed. */
    @Test
    void spendWithoutTokenIsUnauthorized() throws Exception {
        Member member = newMember();
        mockMvc.perform(post("/api/members/{id}/spend", member.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":9005,\"amount\":100.00}"))
                .andExpect(status().isUnauthorized());

        Assertions.assertEquals(0, totalSpendOf(member.getId()).compareTo(BigDecimal.ZERO),
                "An unauthorized request must not change total_spend");
    }

    /** Valid token -> 200 and total_spend increases by the amount. */
    @Test
    void spendWithValidTokenAppliesIncrement() throws Exception {
        Member member = newMember();
        mockMvc.perform(post("/api/members/{id}/spend", member.getId())
                        .header(InternalServiceAuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":9001,\"amount\":100.00}"))
                .andExpect(status().isOk());

        Assertions.assertEquals(0, totalSpendOf(member.getId()).compareTo(new BigDecimal("100.00")),
                "total_spend should increase by the posted amount");
    }

    /** MAJOR/GAP-3: the SAME orderId applied twice increments total_spend exactly once. */
    @Test
    void spendIsIdempotentForSameOrderId() throws Exception {
        Member member = newMember();
        String body = "{\"orderId\":9002,\"amount\":100.00}";

        mockMvc.perform(post("/api/members/{id}/spend", member.getId())
                        .header(InternalServiceAuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        // Replay of the same orderId: still 200, but must NOT double-apply.
        mockMvc.perform(post("/api/members/{id}/spend", member.getId())
                        .header(InternalServiceAuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        Assertions.assertEquals(0, totalSpendOf(member.getId()).compareTo(new BigDecimal("100.00")),
                "A duplicate orderId must be applied only once (idempotent)");
    }

    /** MAJOR: negative amount -> 400 (and no mutation). */
    @Test
    void spendWithNegativeAmountIsBadRequest() throws Exception {
        Member member = newMember();
        mockMvc.perform(post("/api/members/{id}/spend", member.getId())
                        .header(InternalServiceAuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":9003,\"amount\":-5.00}"))
                .andExpect(status().isBadRequest());

        Assertions.assertEquals(0, totalSpendOf(member.getId()).compareTo(BigDecimal.ZERO),
                "A rejected (negative) amount must not change total_spend");
    }

    /** MAJOR: missing amount -> 400. */
    @Test
    void spendWithMissingAmountIsBadRequest() throws Exception {
        Member member = newMember();
        mockMvc.perform(post("/api/members/{id}/spend", member.getId())
                        .header(InternalServiceAuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":9004}"))
                .andExpect(status().isBadRequest());
    }

    /** MAJOR: missing orderId (the idempotency key) -> 400. */
    @Test
    void spendWithMissingOrderIdIsBadRequest() throws Exception {
        Member member = newMember();
        mockMvc.perform(post("/api/members/{id}/spend", member.getId())
                        .header(InternalServiceAuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00}"))
                .andExpect(status().isBadRequest());
    }
}
