package org.jboss.as.quickstarts.kitchensink.users;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;

import org.jboss.as.quickstarts.kitchensink.users.config.CallerAuthenticator;
import org.jboss.as.quickstarts.kitchensink.users.config.InternalServiceAuthInterceptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Web-edge authorization integration test for the member READ surface of {@code /api/members},
 * driven through {@link MockMvc} so the real Spring MVC chain -- including
 * {@link org.jboss.as.quickstarts.kitchensink.users.config.MemberAccessControlInterceptor} -- is
 * exercised.
 *
 * <p>This test resolves the CRITICAL authorization / PII-exposure finding on
 * {@code MemberResourceRESTService}: before the fix, the member listing, single-member lookup, and
 * tier read were fully public, exposing member PII to any unauthenticated caller. It asserts the
 * policy now enforced:</p>
 * <ul>
 *   <li><b>{@code GET /api/members}</b> (all-member PII listing): anonymous -> <b>401</b>, an
 *       authenticated individual member -> <b>403</b>, a trusted SERVICE caller -> <b>200</b>;</li>
 *   <li><b>{@code GET /api/members/{id}}</b> and <b>{@code GET /api/members/{id}/tier}</b>: anonymous
 *       -> <b>401</b>, a non-owner member -> <b>403</b>, the owner and a SERVICE caller -> <b>200</b>
 *       (the SERVICE path is exactly how orders-service reads a member's tier);</li>
 *   <li><b>{@code POST /api/members}</b> (registration): stays intentionally UNAUTHENTICATED -> a
 *       valid anonymous create still returns <b>200</b> (AAP member-create parity), proving the
 *       authorization layer does not regress registration.</li>
 * </ul>
 *
 * <p>Member access tokens are minted with the production {@link CallerAuthenticator#mintMemberToken}
 * so the exact HMAC scheme the interceptor enforces is verified (a forged token is rejected). The
 * registration rate limit is raised far above what this test issues so the permitAll create is never
 * throttled. Runs against a real Testcontainers PostgreSQL loaded with {@code db/01_schema.sql} +
 * {@code db/03_seed_data.sql} (members 1/2/3 seeded).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public class MemberResourceAccessControlIT {

    private static final String TEST_INTERNAL_TOKEN = "test-internal-token";

    /** Seeded member 2 (Robert Torres, SILVER) - the resource owner. */
    private static final Long OWNER_ID = 2L;
    /** Seeded member 1 (Jane Smith) - an authenticated but NON-owning caller. */
    private static final Long OTHER_ID = 1L;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // security.internal.token has no default (CWE-798 fix); supply explicitly so the context
        // starts and member tokens are minted/verified with it. Rate limit set high so the single
        // permitAll registration assertion is never throttled.
        registry.add("security.internal.token", () -> TEST_INTERNAL_TOKEN);
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
    private CallerAuthenticator callerAuthenticator;

    // ----- request-builder helpers -----

    private MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder builder, Long memberId) {
        return builder
                .header(CallerAuthenticator.AUTH_MEMBER_ID_HEADER, memberId)
                .header(CallerAuthenticator.AUTH_MEMBER_TOKEN_HEADER,
                        callerAuthenticator.mintMemberToken(memberId));
    }

    private MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder builder) {
        return builder.header(InternalServiceAuthInterceptor.INTERNAL_TOKEN_HEADER, TEST_INTERNAL_TOKEN);
    }

    // ===================== GET /api/members (list all member PII) =====================

    @Test
    void listMembersAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/members")).andExpect(status().isUnauthorized());
    }

    @Test
    void listMembersAsIndividualMemberIsForbidden() throws Exception {
        // An authenticated individual member must not be able to list every member's PII.
        mockMvc.perform(asMember(get("/api/members"), OWNER_ID)).andExpect(status().isForbidden());
    }

    @Test
    void listMembersAsServiceIsOk() throws Exception {
        mockMvc.perform(asService(get("/api/members"))).andExpect(status().isOk());
    }

    // ===================== GET /api/members/{id} (lookup) =====================

    @Test
    void lookupAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/members/{id}", OWNER_ID)).andExpect(status().isUnauthorized());
    }

    @Test
    void lookupAsNonOwnerIsForbidden() throws Exception {
        mockMvc.perform(asMember(get("/api/members/{id}", OWNER_ID), OTHER_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void lookupAsOwnerIsOk() throws Exception {
        mockMvc.perform(asMember(get("/api/members/{id}", OWNER_ID), OWNER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(OWNER_ID));
    }

    @Test
    void lookupAsServiceIsOk() throws Exception {
        mockMvc.perform(asService(get("/api/members/{id}", OWNER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(OWNER_ID));
    }

    // ===================== GET /api/members/{id}/tier =====================

    @Test
    void tierAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/members/{id}/tier", OWNER_ID)).andExpect(status().isUnauthorized());
    }

    @Test
    void tierAsNonOwnerIsForbidden() throws Exception {
        mockMvc.perform(asMember(get("/api/members/{id}/tier", OWNER_ID), OTHER_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void tierAsOwnerIsOk() throws Exception {
        mockMvc.perform(asMember(get("/api/members/{id}/tier", OWNER_ID), OWNER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("SILVER"));
    }

    @Test
    void tierAsServiceIsOk() throws Exception {
        // This is exactly the cross-service path orders-service uses (UsersClient.getMemberTier
        // presents the shared service token to read any member's tier).
        mockMvc.perform(asService(get("/api/members/{id}/tier", OWNER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("SILVER"));
    }

    // ===================== 401 - forged member token =====================

    @Test
    void forgedMemberTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/members/{id}", OWNER_ID)
                        .header(CallerAuthenticator.AUTH_MEMBER_ID_HEADER, OWNER_ID)
                        .header(CallerAuthenticator.AUTH_MEMBER_TOKEN_HEADER, "forged-token"))
                .andExpect(status().isUnauthorized());
    }

    // ===================== POST /api/members (registration stays public) =====================

    @Test
    void registrationStaysUnauthenticatedAndReturns200() throws Exception {
        // The authorization layer must NOT block the intentionally public registration endpoint:
        // a valid anonymous create still returns 200 OK (AAP member-create parity).
        String body = "{\"name\":\"Access Control Tester\","
                + "\"email\":\"authz-newmember@example.com\","
                + "\"phoneNumber\":\"9195550000\"}";
        mockMvc.perform(post("/api/members")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
