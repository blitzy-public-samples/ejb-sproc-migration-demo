package org.jboss.as.quickstarts.kitchensink.users;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;

import org.jboss.as.quickstarts.kitchensink.users.model.Member;
import org.jboss.as.quickstarts.kitchensink.users.service.MemberRegistration;
import org.junit.jupiter.api.Assertions;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Self-contained users-service registration integration test.
 *
 * <p>Migrated from the legacy Arquillian/JUnit&nbsp;4 test at
 * {@code kitchensink/src/test/java/.../kitchensink/test/MemberRegistrationIT.java} to
 * {@code @SpringBootTest} + Testcontainers + JUnit&nbsp;5. It exercises the real
 * {@link MemberRegistration} Spring bean against a real PostgreSQL database started by
 * Testcontainers, preserving the original behavioral-parity assertion (a registered member
 * is persisted and assigned a generated, non-null id).</p>
 *
 * <p>This class lives DIRECTLY in the base package
 * {@code org.jboss.as.quickstarts.kitchensink.users} so {@code @SpringBootTest} auto-discovers
 * {@link UsersApplication} by walking up the package tree; no {@code classes=} attribute is
 * required. No downstream HTTP stubbing is needed: registration touches only the owned
 * {@code member} table (AAP &sect;0.6.3 — "None — self-contained").</p>
 *
 * <p>The class is intentionally NOT {@code @Transactional}: the registration commit must
 * persist so the database-generated id is real and observable on the in-memory entity.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MemberRegistrationIT {

    /**
     * Disposable PostgreSQL instance. The Testcontainers JUnit&nbsp;5 extension starts this static
     * container before any user {@code @BeforeAll}, and its lifecycle spans the whole test class.
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Points Spring's datasource at the running container. Only the connection coordinates are
     * overridden; all other JPA settings (notably {@code spring.jpa.hibernate.ddl-auto=validate}
     * and the PostgreSQL dialect) come from {@code application.properties}.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // security.internal.token has no default in application.properties (CWE-798 fix); supply an
        // explicit value so the context (CallerAuthenticator / InternalServiceAuthInterceptor /
        // OrdersClient) starts. Raise the registration rate limit far above anything this test issues
        // so the public POST /api/members registrations are never throttled here.
        registry.add("security.internal.token", () -> "test-internal-token");
        registry.add("security.registration.max-per-minute", () -> "1000000");
    }

    /**
     * Creates the owned schema and loads seed data BEFORE the Spring context boots, so Hibernate's
     * {@code validate} sees the {@code member} table. Loads {@code db/01_schema.sql} then
     * {@code db/03_seed_data.sql} ONLY — never the stored procedures (the migrated services issue
     * zero native queries). After the seed runs {@code setval('member_id_seq', MAX(id))}, the next
     * insert receives id&nbsp;4, so the test member does not collide with the three seeded members.
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
    private MemberRegistration memberRegistration;

    @Autowired
    private MockMvc mockMvc;

    /**
     * Registers a new member and asserts the database assigned a non-null generated id, mirroring
     * the legacy test exactly. Declares {@code throws Exception} because
     * {@link MemberRegistration#register(Member)} does.
     */
    @Test
    void testRegister() throws Exception {
        Member newMember = new Member();
        newMember.setName("Jane Doe");
        newMember.setEmail("jane@mailinator.com");
        newMember.setPhoneNumber("2125551234");

        memberRegistration.register(newMember);

        Assertions.assertNotNull(newMember.getId(),
                newMember.getName() + " should have been persisted with a non-null id");
    }

    /**
     * Duplicate-email REST semantics (checkpoint coverage gap). Drives the real
     * {@code POST /api/members} edge through {@link MockMvc}: the first create succeeds with 200 OK
     * (the legacy {@code Response.ok().build()} contract, preserved), and a second create with the
     * SAME email is rejected by the controller's duplicate-email guard as 409 Conflict
     * (the {@code ValidationException} -> {@code handleDuplicateEmail} mapping).
     *
     * <p>The email is unique to this test (it is neither a seeded address nor the one used by
     * {@link #testRegister()}), so the assertion is independent of test execution order.</p>
     */
    @Test
    void testDuplicateEmailReturns409() throws Exception {
        String json = "{\"name\":\"Dup Tester\","
                + "\"email\":\"dup.f13@mailinator.com\","
                + "\"phoneNumber\":\"2125550000\"}";

        // First registration of this email -> 200 OK.
        mockMvc.perform(post("/api/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        // Re-registering the same email -> 409 Conflict (duplicate-email semantics preserved).
        mockMvc.perform(post("/api/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict());
    }

    /**
     * Bean-Validation REST semantics (checkpoint coverage gap). A malformed member payload is
     * rejected with 400 Bad Request before any persistence. The payload violates three constraints
     * at once -- {@code name} contains digits ({@code @Pattern("[^0-9]*")}), {@code email} is not a
     * valid address ({@code @Email}), and {@code phoneNumber} is non-numeric and too short
     * ({@code @Size}/{@code @Digits}) -- exercising the {@code @Valid @RequestBody Member} path that
     * resolves to {@code handleValidationErrors} (400).
     */
    @Test
    void testInvalidMemberReturns400() throws Exception {
        String invalidJson = "{\"name\":\"John123\","
                + "\"email\":\"not-an-email\","
                + "\"phoneNumber\":\"abc\"}";

        mockMvc.perform(post("/api/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }
}
