package org.jboss.as.quickstarts.kitchensink.users;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RemoteMemberRegistrationIT - users-service HTTP black-box registration test.
 *
 * <p>Migrated from the legacy Arquillian/JUnit&nbsp;4 black-box test
 * {@code kitchensink/src/test/java/.../kitchensink/test/RemoteMemberRegistrationIT.java} to
 * {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} + Testcontainers + JUnit&nbsp;5 (AAP
 * &sect;0.4.1 users-service test row, &sect;0.6.3 stub table "None - HTTP black-box", G6). The full
 * users-service boots on a random port and a member is POSTed over real HTTP, asserting the
 * preserved producer contract: HTTP&nbsp;200 with an EMPTY body (deliberately NOT 201, per AAP
 * &sect;0.6.7 - this is the regression guard the production Javadoc cites as
 * "RemoteMemberRegistrationIT parity").</p>
 *
 * <p>This is a TRUE black box: it imports no production class (not even {@code Member}); the request
 * body is a plain JSON String and the response is asserted purely on status code + body, using the
 * JDK-built-in {@code java.net.http.HttpClient} (faithful to the legacy transport and honoring the
 * cross-domain boundary rule). The legacy {@code SERVER_HOST}/{@code server.host} env-var host
 * resolution is replaced by {@link LocalServerPort} since Spring injects the random port.</p>
 *
 * <p>This class lives DIRECTLY in the base package
 * {@code org.jboss.as.quickstarts.kitchensink.users} so {@code @SpringBootTest} auto-discovers
 * {@link UsersApplication} by walking up the package tree; no {@code classes=} attribute is required.
 * The schema and seed are loaded via direct JDBC in {@code @BeforeAll} BEFORE the context (and
 * embedded Tomcat) boots, because {@code spring.jpa.hibernate.ddl-auto=validate} requires the
 * {@code member} table to exist. Only {@code db/01_schema.sql} then {@code db/03_seed_data.sql} are
 * loaded - never {@code 02_stored_procedures.sql} (the migrated service issues zero native queries).
 * The seed's {@code setval('member_id_seq', MAX(id))} makes the POSTed member id&nbsp;4, and
 * {@code jane@mailinator.com} collides with no seeded email, so registration returns 200.</p>
 *
 * <p>The class is intentionally NOT {@code @Transactional}: the registration commit must persist over
 * the real HTTP round-trip.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RemoteMemberRegistrationIT {

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
    }

    /**
     * Creates the owned schema and loads seed data BEFORE the Spring context boots, so Hibernate's
     * {@code validate} sees the {@code member} table. Loads {@code db/01_schema.sql} then
     * {@code db/03_seed_data.sql} ONLY - never the stored procedures (the migrated services issue
     * zero native queries).
     */
    @BeforeAll
    static void loadSchemaAndSeed() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/01_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/03_seed_data.sql"));
        }
    }

    /** Random port the embedded Tomcat bound to; injected by Spring Boot (Boot 3.x package). */
    @LocalServerPort
    private int port;

    /**
     * POSTs a valid member over real HTTP to {@code /users/api/members} (context-path {@code /users}
     * + controller mapping {@code /api/members}) and asserts the preserved producer contract: status
     * 200 AND an empty body. {@code ResponseEntity.ok().build()} yields a zero-length body, so
     * {@code HttpResponse.BodyHandlers.ofString()} returns {@code ""}.
     */
    @Test
    void testRegister() throws Exception {
        String json = "{\"name\":\"Jane Doe\","
                + "\"email\":\"jane@mailinator.com\","
                + "\"phoneNumber\":\"2125551234\"}";

        URI endpoint = URI.create("http://localhost:" + port + "/users/api/members");

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        Assertions.assertEquals(200, response.statusCode(),
                "POST /users/api/members should return HTTP 200 (status preserved, not 201)");
        Assertions.assertEquals("", response.body(),
                "Member-create response body should be empty");
    }
}
