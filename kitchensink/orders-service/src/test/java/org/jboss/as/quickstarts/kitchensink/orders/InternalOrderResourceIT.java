package org.jboss.as.quickstarts.kitchensink.orders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;

import org.jboss.as.quickstarts.kitchensink.orders.config.InternalServiceAuthInterceptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Web-edge integration test for {@code InternalOrderResourceRESTService}
 * ({@code GET /internal/members/{id}/spend?days=}), driven through {@link MockMvc} so the real
 * Spring MVC chain -- including {@link InternalServiceAuthInterceptor} and the controller's
 * built-in method validation -- is exercised.
 *
 * <p>Covers the two web-edge findings on this endpoint:</p>
 * <ul>
 *   <li><b>Access control (MAJOR security).</b> The internal endpoint discloses member spend and
 *       must reject callers that do not present a valid service token: a request WITHOUT the
 *       {@value InternalServiceAuthInterceptor#INTERNAL_TOKEN_HEADER} header is 401; a request WITH
 *       the configured token is served (200).</li>
 *   <li><b>Input validation (MEDIUM robustness).</b> {@code days} is bounded to [1, 365]; values
 *       outside the range (0, 400) are rejected with 400 by Spring's controller method validation
 *       (which runs after the interceptor authorizes the caller).</li>
 * </ul>
 *
 * <p>Like the other orders ITs, this test runs against a real Testcontainers PostgreSQL loaded with
 * {@code db/01_schema.sql} + {@code db/03_seed_data.sql} (so {@code ddl-auto=validate} succeeds at
 * context startup). MockMvc dispatches at the servlet level, so the {@code /orders} context-path is
 * not part of the request URI and the interceptor's {@code /internal/**} pattern matches directly.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public class InternalOrderResourceIT {

    private static final Long TEST_MEMBER_ID = 2L;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Test-only value for the now-required (no-default) shared service token. */
    private static final String TEST_INTERNAL_TOKEN = "test-internal-token";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // security.internal.token has no default in application.properties (CWE-798 fix); the test
        // supplies an explicit value here so the @Value injection and token guard work under test.
        registry.add("security.internal.token", () -> TEST_INTERNAL_TOKEN);
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

    /** The configured shared service token (supplied by {@link #datasourceProperties} under test). */
    @Value("${security.internal.token}")
    private String internalToken;

    /** No token -> 401 Unauthorized: an unauthenticated caller cannot read member spend. */
    @Test
    void spendWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/internal/members/{id}/spend", TEST_MEMBER_ID).param("days", "90"))
                .andExpect(status().isUnauthorized());
    }

    /** Wrong token -> 401 Unauthorized. */
    @Test
    void spendWithWrongTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/internal/members/{id}/spend", TEST_MEMBER_ID)
                        .header(InternalServiceAuthInterceptor.INTERNAL_TOKEN_HEADER, "not-the-token")
                        .param("days", "90"))
                .andExpect(status().isUnauthorized());
    }

    /** Valid token + valid days -> 200 with a numeric totalSpend (0 when no qualifying orders). */
    @Test
    void spendWithValidTokenIsOk() throws Exception {
        mockMvc.perform(get("/internal/members/{id}/spend", TEST_MEMBER_ID)
                        .header(InternalServiceAuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken)
                        .param("days", "90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSpend").isNumber());
    }

    /** Valid token but days=0 (below @Min(1)) -> 400 Bad Request. */
    @Test
    void spendWithDaysZeroIsBadRequest() throws Exception {
        mockMvc.perform(get("/internal/members/{id}/spend", TEST_MEMBER_ID)
                        .header(InternalServiceAuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken)
                        .param("days", "0"))
                .andExpect(status().isBadRequest());
    }

    /** Valid token but days=400 (above @Max(365)) -> 400 Bad Request. */
    @Test
    void spendWithDaysTooLargeIsBadRequest() throws Exception {
        mockMvc.perform(get("/internal/members/{id}/spend", TEST_MEMBER_ID)
                        .header(InternalServiceAuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken)
                        .param("days", "400"))
                .andExpect(status().isBadRequest());
    }
}
