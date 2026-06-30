package org.jboss.as.quickstarts.kitchensink.users;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import org.jboss.as.quickstarts.kitchensink.users.client.OrdersClient;
import org.jboss.as.quickstarts.kitchensink.users.model.Member;
import org.jboss.as.quickstarts.kitchensink.users.service.MemberRegistration;
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
 * Integration test for {@link MemberRegistration} (member-onboarding service of users-service).
 *
 * <p>Faithful rewrite of the monolith's Arquillian/ShrinkWrap JUnit-4 {@code MemberRegistrationIT}
 * into the {@code @SpringBootTest} + Testcontainers (JUnit 5) pattern. Boots a real
 * {@code postgres:16} container, seeds it from the three authoritative {@code db/*.sql} scripts,
 * and runs the full users-service context with {@code ddl-auto=validate} so Hibernate validates the
 * {@code Member} entity against the frozen schema.</p>
 *
 * <p>{@code OrdersClient} is replaced with a {@code @MockBean}: the production
 * {@code TierRecalculationService} declares {@code @EventListener(ApplicationReadyEvent)}, which
 * fires on every context boot and calls {@code ordersClient.getNinetyDaySpend(...)} for every
 * member. Stubbing it as a mock (returning {@code null} -> the service's {@code null -> ZERO} guard)
 * prevents any outbound HTTP to orders-service during context startup, which would otherwise throw
 * {@code ServiceUnavailableException} and fail context load (breaking even this registration test).</p>
 */
@SpringBootTest
@Testcontainers
class MemberRegistrationIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("kitchensink");

    /**
     * Wire the running container's JDBC coordinates into Spring and force {@code ddl-auto=validate}
     * (NEVER create): schema scripts are loaded in {@link #seedDatabase()} first, then Hibernate
     * validates the {@code Member} mapping against the already-seeded schema at context load.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /**
     * Seed the container from the three authoritative scripts IN ORDER. Each file's FULL text is
     * executed as a SINGLE statement (NEVER split on ';') because {@code 02_stored_procedures.sql}
     * contains dollar-quoted ($$) PL/pgSQL bodies whose interior semicolons would corrupt a naive
     * splitter. Module-relative {@code ../db/...} paths resolve because the IT runs with
     * CWD = {@code users-service/}.
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
    private MemberRegistration memberRegistration;

    @MockBean
    private OrdersClient ordersClient;

    /**
     * Registering a valid new member persists it and assigns a generated identity
     * (parity with the monolith's {@code em.persist(member)} behavior).
     */
    @Test
    void testRegister() throws Exception {
        Member newMember = new Member();
        newMember.setName("Jane Doe");
        newMember.setEmail("jane-" + UUID.randomUUID() + "@mailinator.com");
        newMember.setPhoneNumber("2125551234");

        memberRegistration.register(newMember);

        assertNotNull(newMember.getId(), "Member should have a generated id after registration");
    }
}
