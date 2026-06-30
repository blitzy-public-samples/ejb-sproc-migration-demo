package org.jboss.as.quickstarts.kitchensink.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.jboss.as.quickstarts.kitchensink.orders.client.MarketplaceClient;
import org.jboss.as.quickstarts.kitchensink.orders.client.UsersClient;
import org.jboss.as.quickstarts.kitchensink.orders.repository.DiscountAuditRepository;
import org.jboss.as.quickstarts.kitchensink.orders.service.DiscountService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@code DiscountService}, rewritten from the monolith Arquillian test into the
 * orders-service {@code @SpringBootTest} + Testcontainers pattern.
 *
 * <p>Member tier is supplied through Contract 2 by stubbing {@code UsersClient.getMemberTier(...)};
 * the {@code member} table is never read directly (boundary rule). {@code MarketplaceClient} is mocked
 * only so the full orders-service context loads. The legacy {@code PricingService} comparison is dropped
 * because pricing now lives in marketplace-service and must not be imported here.</p>
 */
@SpringBootTest
@Testcontainers
class DiscountServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("kitchensink");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeAll
    static void seedDatabase() throws Exception {
        String[] scripts = {"../db/01_schema.sql", "../db/02_stored_procedures.sql", "../db/03_seed_data.sql"};
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            for (String script : scripts) {
                String sql = Files.readString(Path.of(script), StandardCharsets.UTF_8);
                statement.execute(sql);
            }
        }
    }

    @Autowired
    private DiscountService discountService;

    @Autowired
    private DiscountAuditRepository discountAuditRepository;

    @MockitoBean
    private UsersClient usersClient;

    @MockitoBean
    private MarketplaceClient marketplaceClient;

    @BeforeEach
    void setUp() {
        // Member tier is supplied via Contract 2 (UsersClient), never by reading the member table.
        when(usersClient.getMemberTier(1L)).thenReturn("GOLD");
        when(usersClient.getMemberTier(2L)).thenReturn("SILVER");
        when(usersClient.getMemberTier(3L)).thenReturn("BRONZE");
    }

    @Test
    void testBronzeMemberDiscountIsTwoPercent() {
        BigDecimal discount = discountService.calculateDiscount(3L, new BigDecimal("100.00"));

        assertEquals(0, new BigDecimal("2.00").compareTo(discount),
                "BRONZE discount on 100.00 should be 2.00 (2%)");
    }

    @Test
    void testGoldMemberDiscountIsEightPercent() {
        BigDecimal discount = discountService.calculateDiscount(1L, new BigDecimal("100.00"));

        assertEquals(0, new BigDecimal("8.00").compareTo(discount),
                "GOLD discount on 100.00 should be 8.00 (8%)");
    }

    @Test
    void testDiscountAuditRowCreatedPerCall() {
        long before = discountAuditRepository.count();

        discountService.calculateDiscount(2L, new BigDecimal("50.00"));

        assertEquals(before + 1, discountAuditRepository.count(),
                "exactly one discount_audit row should be inserted per calculateDiscount call");
    }

    @Test
    void testDiscountReducesBaseTotal() {
        BigDecimal base = new BigDecimal("100.00");

        BigDecimal discount = discountService.calculateDiscount(2L, base);

        assertTrue(discount.signum() > 0, "discount should be positive");
        assertTrue(base.subtract(discount).compareTo(base) < 0,
                "base minus discount should be strictly less than base");
    }
}
