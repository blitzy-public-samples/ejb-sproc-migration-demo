package org.jboss.as.quickstarts.kitchensink.users;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;

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
 * Verifies the per-IP abuse control on the public member-registration endpoint
 * ({@code POST /api/members}) added as part of the CRITICAL authorization fix.
 *
 * <p>Registration is intentionally UNAUTHENTICATED (AAP member-create parity: 200 OK, no auth), so a
 * rate limiter is the abuse control for the open write endpoint. This test pins
 * {@code security.registration.max-per-minute=2} via {@link DynamicPropertySource} and, from a single
 * client IP within one fixed window, submits three DISTINCT, VALID registration payloads:</p>
 * <ul>
 *   <li>the first two are within the cap -> <b>200 OK</b>;</li>
 *   <li>the third exceeds the cap -> <b>429 Too Many Requests</b>.</li>
 * </ul>
 *
 * <p>All three payloads are valid and use distinct, non-seeded emails, so the 429 is unambiguously
 * the rate limiter (not a 400 validation failure or a 409 duplicate-email response). A dedicated test
 * class with a single test method is used so the shared-IP / shared-window request counting cannot
 * leak across methods.</p>
 *
 * <p><b>CWE-348 regression guard.</b> Each request additionally carries a DISTINCT, spoofed
 * {@code X-Forwarded-For} value. Since the limiter keys strictly on the direct peer address and ignores
 * the client-controlled forwarded header, the three requests still share one counter (200, 200, 429).
 * Were the limiter to trust {@code X-Forwarded-For} again, the rotated values would look like three
 * separate clients and the third request would return 200, failing this test.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public class RegistrationRateLimitIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("security.internal.token", () -> "test-internal-token");
        // Deliberately low so the third registration trips the limiter.
        registry.add("security.registration.max-per-minute", () -> "2");
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

    private static String registration(String emailLocalPart) {
        return "{\"name\":\"Rate Limit Tester\","
                + "\"email\":\"" + emailLocalPart + "@example.com\","
                + "\"phoneNumber\":\"9195551111\"}";
    }

    @Test
    void registrationIsRateLimitedPerIp() throws Exception {
        // Each request carries a DISTINCT, spoofed X-Forwarded-For value. Because the limiter keys
        // strictly on the direct peer address (getRemoteAddr(), which MockMvc fixes at 127.0.0.1) and
        // deliberately ignores the client-controlled X-Forwarded-For header, all three requests share
        // one counter key. This is the CWE-348 regression guard: if the limiter ever again trusted
        // X-Forwarded-For, the three distinct header values would be treated as three separate clients
        // and the third request would return 200 instead of 429, failing this test.

        // First two within the cap of 2 -> 200 OK (despite distinct spoofed XFF values).
        mockMvc.perform(post("/api/members")
                        .header("X-Forwarded-For", "10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON).content(registration("ratelimit1")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/members")
                        .header("X-Forwarded-For", "10.0.0.2")
                        .contentType(MediaType.APPLICATION_JSON).content(registration("ratelimit2")))
                .andExpect(status().isOk());

        // Third exceeds the cap -> 429 Too Many Requests (rejected before the controller), proving a
        // rotated X-Forwarded-For cannot reset the per-IP counter.
        mockMvc.perform(post("/api/members")
                        .header("X-Forwarded-For", "203.0.113.9")
                        .contentType(MediaType.APPLICATION_JSON).content(registration("ratelimit3")))
                .andExpect(status().isTooManyRequests());
    }
}
