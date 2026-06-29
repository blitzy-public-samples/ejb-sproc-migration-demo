package org.jboss.as.quickstarts.kitchensink.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.jboss.as.quickstarts.kitchensink.data.OrderDraftItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * REST input-validation integration tests for findings F4 and F5 (MINOR — Security / Input
 * Validation), exercised over real HTTP with a {@link TestRestTemplate} against a random-port server.
 *
 * <ul>
 *   <li><strong>F4 — add-to-cart id validation</strong> ({@code POST /api/orders/cart/{memberId}}):
 *       non-positive {@code memberId}/{@code productId} must yield <strong>400</strong>, and a
 *       well-formed-but-unknown member or product must yield <strong>404</strong> (instead of an
 *       FK-backed 500). The existing 201 happy-path contract must be preserved.</li>
 *   <li><strong>F5 — product quantity validation</strong> ({@code GET /api/products/{id}/vendors} and
 *       {@code GET /api/products/{id}/price}): a zero or negative {@code quantity} must yield
 *       <strong>400</strong>; the price happy path must still return <strong>200</strong> with the
 *       numeric unit price.</li>
 * </ul>
 *
 * <p>The {@code test} profile resets {@code server.servlet.context-path} to root, so URLs here use
 * {@code /api/...} (no {@code /kitchensink} prefix), consistent with {@code RemoteMemberRegistrationIT}.
 * Seed data provides member 3 (Emily Chen), product 1, and vendor 1. Member 3 is used for the
 * happy-path add so this test never disturbs the member-2 cart that {@code OrderServiceIT} manages; the
 * member-3 draft cart is cleared before and after each test for isolation.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class RestValidationIT {

    private static final Long HAPPY_MEMBER_ID    = 3L;
    private static final Long EXISTING_PRODUCT_ID = 1L;
    private static final Long EXISTING_VENDOR_ID  = 1L;
    private static final Long MISSING_ID          = 999999L;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderDraftItemRepository orderDraftItemRepository;

    @LocalServerPort
    private int port;

    // Run before AND after each test: keep member 3's draft cart empty so the happy-path add is
    // isolated and leaves no residue for other tests.
    @BeforeEach
    @AfterEach
    public void cleanHappyMemberCart() {
        orderDraftItemRepository.deleteByMemberId(HAPPY_MEMBER_ID);
    }

    // ---- Helpers ----

    private String ordersBase() {
        return "http://localhost:" + port + "/api/orders";
    }

    private String productsBase() {
        return "http://localhost:" + port + "/api/products";
    }

    /**
     * POSTs an add-to-cart request. {@code memberIdPath} is rendered verbatim into the path (so callers
     * can supply 0 or negative values); {@code productId}/{@code quantity} populate the JSON body.
     */
    private ResponseEntity<String> postAddToCart(Object memberIdPath, Long productId, Integer quantity) {
        String url = ordersBase() + "/cart/" + memberIdPath;
        Map<String, Object> body = new HashMap<>();
        if (productId != null) {
            body.put("productId", productId);
        }
        if (quantity != null) {
            body.put("quantity", quantity);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        return restTemplate.postForEntity(url, request, String.class);
    }

    // ---- F4: add-to-cart id validation ----

    @Test
    public void testAddToCartRejectsNonPositiveMemberId() {
        assertEquals(400, postAddToCart(0L, EXISTING_PRODUCT_ID, 1).getStatusCode().value(),
            "memberId = 0 must be rejected with 400");
        assertEquals(400, postAddToCart(-5L, EXISTING_PRODUCT_ID, 1).getStatusCode().value(),
            "negative memberId must be rejected with 400");
    }

    @Test
    public void testAddToCartRejectsNonPositiveProductId() {
        assertEquals(400, postAddToCart(HAPPY_MEMBER_ID, 0L, 1).getStatusCode().value(),
            "productId = 0 must be rejected with 400");
        assertEquals(400, postAddToCart(HAPPY_MEMBER_ID, -3L, 1).getStatusCode().value(),
            "negative productId must be rejected with 400");
    }

    @Test
    public void testAddToCartUnknownMemberReturns404() {
        // memberId is positive but does not exist; productId is a real product so the only failure is
        // the missing member -> MemberNotFoundException -> 404.
        ResponseEntity<String> response = postAddToCart(MISSING_ID, EXISTING_PRODUCT_ID, 1);
        assertEquals(404, response.getStatusCode().value(),
            "unknown member must yield 404, not an FK-backed 500");
    }

    @Test
    public void testAddToCartUnknownProductReturns404() {
        // Existing member, positive but unknown productId -> InventoryNotFoundException -> 404.
        ResponseEntity<String> response = postAddToCart(HAPPY_MEMBER_ID, MISSING_ID, 1);
        assertEquals(404, response.getStatusCode().value(),
            "unknown product must yield 404, not an FK-backed 500");
    }

    @Test
    public void testAddToCartHappyPathReturns201() {
        // Existing member + product + positive quantity must preserve the legacy 201 contract.
        ResponseEntity<String> response = postAddToCart(HAPPY_MEMBER_ID, EXISTING_PRODUCT_ID, 2);
        assertEquals(201, response.getStatusCode().value(),
            "valid add-to-cart must still return 201");
        assertEquals(1, orderDraftItemRepository.findByMemberId(HAPPY_MEMBER_ID).size(),
            "valid add-to-cart must insert exactly one draft row");
    }

    // ---- F5: product vendors/price quantity validation ----

    @Test
    public void testVendorsRejectsNonPositiveQuantity() {
        String base = productsBase() + "/" + EXISTING_PRODUCT_ID + "/vendors?quantity=";
        assertEquals(400, restTemplate.getForEntity(base + "0", String.class).getStatusCode().value(),
            "vendors quantity = 0 must be rejected with 400");
        assertEquals(400, restTemplate.getForEntity(base + "-3", String.class).getStatusCode().value(),
            "vendors negative quantity must be rejected with 400");
    }

    @Test
    public void testPriceRejectsNonPositiveQuantity() {
        String base = productsBase() + "/" + EXISTING_PRODUCT_ID + "/price?vendorId="
            + EXISTING_VENDOR_ID + "&quantity=";
        assertEquals(400, restTemplate.getForEntity(base + "0", String.class).getStatusCode().value(),
            "price quantity = 0 must be rejected with 400");
        assertEquals(400, restTemplate.getForEntity(base + "-2", String.class).getStatusCode().value(),
            "price negative quantity must be rejected with 400");
    }

    @Test
    public void testPriceHappyPathReturns200WithPositivePrice() {
        String url = productsBase() + "/" + EXISTING_PRODUCT_ID + "/price?vendorId="
            + EXISTING_VENDOR_ID + "&quantity=1";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        assertEquals(200, response.getStatusCode().value(), "valid price request must return 200");
        String bodyText = response.getBody();
        assertNotNull(bodyText, "price response body must not be null");
        assertTrue(new BigDecimal(bodyText.trim()).compareTo(BigDecimal.ZERO) > 0,
            "unit price must be a positive number; was: " + bodyText);
    }
}
