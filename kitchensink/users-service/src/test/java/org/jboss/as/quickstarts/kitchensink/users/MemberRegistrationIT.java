package org.jboss.as.quickstarts.kitchensink.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.jboss.as.quickstarts.kitchensink.users.service.MemberRegistration;
import org.jboss.as.quickstarts.kitchensink.users.service.MemberSpendService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
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
 * <p>{@code OrdersClient} is replaced with a {@code @MockitoBean}: the production
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

    @Autowired
    private MemberSpendService memberSpendService;

    @Autowired
    private MemberRepository memberRepository;

    @MockitoBean
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

    /**
     * Source-A {@code process_order} side-effect (AAP &sect;0.6.1 / &sect;0.7.3): orders-service
     * increments a member's lifetime {@code total_spend} by the order SUBTOTAL through the internal
     * write contract. This verifies the producer side ({@link MemberSpendService}): the increment is
     * added to the existing total and the loyalty {@code tier} is left untouched (tier is driven only
     * by the 90-day rolling spend, AAP &sect;0.6.5).
     */
    @Test
    void testIncrementTotalSpendAddsSubtotalAndPreservesTier() {
        Member member = new Member();
        member.setName("Spend Tester");
        member.setEmail("spend-" + UUID.randomUUID() + "@mailinator.com");
        member.setPhoneNumber("2125559876");
        member.setTier("BRONZE");
        member.setTotalSpend(new BigDecimal("100.00"));
        member = memberRepository.save(member);
        Long id = member.getId();

        memberSpendService.incrementTotalSpend(id, new BigDecimal("49.99"));

        Member updated = memberRepository.findById(id).orElseThrow();
        assertEquals(0, updated.getTotalSpend().compareTo(new BigDecimal("149.99")),
                "total_spend must increase by the order subtotal (100.00 + 49.99 = 149.99)");
        assertEquals("BRONZE", updated.getTier(),
                "Incrementing lifetime spend must NOT change the loyalty tier");
    }

    /**
     * The internal spend-increment write contract returns 404 for an unknown member: the service
     * raises {@link ResponseStatusException} with {@link HttpStatus#NOT_FOUND}, which the consumer
     * (orders-service {@code UsersClient}) maps to {@code MemberNotFoundException}.
     */
    @Test
    void testIncrementTotalSpendUnknownMemberThrowsNotFound() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> memberSpendService.incrementTotalSpend(999_999L, new BigDecimal("10.00")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode(),
                "Incrementing spend for a non-existent member must yield 404 NOT_FOUND");
    }
}
