package org.jboss.as.quickstarts.kitchensink.orders;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import org.jboss.as.quickstarts.kitchensink.orders.rest.OrderResourceRESTService;
import org.jboss.as.quickstarts.kitchensink.orders.service.OrderService;

/**
 * Regression guard for QA finding ORD-CONC-1: a concurrent double-submit of the SAME cart must
 * surface as a graceful HTTP 409 Conflict, never an unhandled HTTP 500.
 *
 * <p><strong>What broke.</strong> {@code POST /api/orders/submit/{memberId}} flows through
 * {@code OrderService.submitOrder()} -> {@code OrderPersistenceService.persistConfirmedOrder()},
 * whose single {@code @Transactional} boundary persists the CONFIRMED order and clears the member's
 * draft cart. Under a near-simultaneous double-submit, the winning transaction deletes the
 * {@code order_draft_items} rows and commits; the losing transaction then deletes the same (now-gone)
 * rows and Hibernate's row-count check raises {@code org.hibernate.StaleObjectStateException}, which
 * Spring wraps as {@link ObjectOptimisticLockingFailureException}. Before the fix that exception was
 * unmapped and leaked as a generic 500; now
 * {@code OrderResourceRESTService.handleConcurrentSubmit(...)} maps it to 409 with a meaningful body,
 * mirroring the users-service registration edge's concurrent duplicate-email -> 409 pattern.</p>
 *
 * <p><strong>Why standalone MockMvc.</strong> This test verifies the fix at its true unit -- the
 * controller's exception-to-status mapping -- deterministically and without flakiness. It drives the
 * real {@link OrderResourceRESTService} through {@link MockMvcBuilders#standaloneSetup(Object...)}
 * (which registers the controller-local {@code @ExceptionHandler} methods) over a Mockito-stubbed
 * {@link OrderService}, so no database, Testcontainers, or peer service is required. The end-to-end
 * behavior of the actual concurrency race (one 201, one 409) is additionally re-verified at runtime
 * against live services during QA remediation. The class honors the cross-domain boundary rule
 * (AAP &sect;0.7.2): it references only orders-service production types.</p>
 */
class OrderSubmitConcurrencyIT {

    /** Member 2 (Robert Torres) mirrors the ORD-CONC-1 reproduction; exact value is immaterial to the mapping. */
    private static final Long TEST_MEMBER_ID = 2L;

    /** Destination ZIP from the ORD-CONC-1 reproduction steps. */
    private static final String TEST_ZIP = "27601";

    private OrderService orderService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Mockito-stubbed service so the test isolates the controller's exception->status mapping.
        orderService = mock(OrderService.class);
        // standaloneSetup registers the controller's @ExceptionHandler methods, so the concurrency
        // exception thrown out of the submit handler is routed to handleConcurrentSubmit(...).
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderResourceRESTService(orderService)).build();
    }

    /**
     * The losing request of a double-submit race: {@code submitOrder()} throws
     * {@link ObjectOptimisticLockingFailureException} (the stale draft-cart delete) -> the edge must
     * respond 409 Conflict with body {@code {"error":"Cart already submitted; please retry."}}
     * (NOT a 500).
     */
    @Test
    void concurrentDoubleSubmitLoserIsMappedToGraceful409() throws Exception {
        when(orderService.submitOrder(anyLong(), anyString(), anyBoolean()))
                .thenThrow(new ObjectOptimisticLockingFailureException(
                        "Row was updated or deleted by another transaction "
                                + "(or unsaved-value mapping was incorrect): OrderDraftItem#8",
                        new RuntimeException("org.hibernate.StaleObjectStateException")));

        mockMvc.perform(post("/api/orders/submit/{memberId}", TEST_MEMBER_ID).param("zip", TEST_ZIP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Cart already submitted; please retry."));
    }

    /**
     * The winning request (and every ordinary submit) is unaffected: {@code submitOrder()} returns the
     * persisted order id and the edge responds 201 Created with {@code {"orderId":N}}. This proves the
     * new handler is TARGETED to the concurrency exception and does not intercept the happy path.
     */
    @Test
    void ordinarySubmitStillReturns201WithOrderId() throws Exception {
        when(orderService.submitOrder(anyLong(), anyString(), anyBoolean())).thenReturn(777L);

        mockMvc.perform(post("/api/orders/submit/{memberId}", TEST_MEMBER_ID).param("zip", TEST_ZIP))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(777));
    }
}
