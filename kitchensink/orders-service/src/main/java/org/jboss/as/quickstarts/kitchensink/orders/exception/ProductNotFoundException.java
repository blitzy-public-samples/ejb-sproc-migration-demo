package org.jboss.as.quickstarts.kitchensink.orders.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a cart operation references a product that does not exist in the marketplace catalog.
 *
 * <p><strong>Why this exists (QA Issue 2 — add-to-cart error handling).</strong> The
 * {@code order_draft_items} table has a foreign key to {@code product}. Before this exception,
 * {@code POST /api/orders/cart/{memberId}} with an unknown {@code productId} was persisted blindly and
 * the database rejected it with a {@code order_draft_items_product_id_fkey} violation, surfacing to
 * the client as an uncontrolled HTTP 500 with a {@code DataIntegrityViolationException} and FK stack
 * trace in the logs. orders-service owns no {@code Product} entity (boundary rule, AAP &sect;0.7.2),
 * so it verifies product existence over HTTP via
 * {@link org.jboss.as.quickstarts.kitchensink.orders.client.MarketplaceClient#verifyProductExists(Long)}
 * <em>before</em> persisting a draft row and raises this exception when the product is absent.</p>
 *
 * <p>This is the product-side analogue of {@link MemberNotFoundException}: both signal that an entity
 * referenced by the cart does not exist and both map to HTTP 404. It is intentionally distinct from
 * {@link InventoryNotFoundException}, which means "the product exists but no vendor inventory prices
 * it" (a Contract 1 pricing condition), so the two conditions are not conflated.</p>
 *
 * <p>The {@link ResponseStatus} annotation maps the exception to HTTP 404 if it propagates out of a
 * Spring MVC handler; the rest layer also maps it explicitly via {@code @RestControllerAdvice} (which
 * takes precedence and returns a sanitized JSON body). Being a {@link RuntimeException}, it also
 * triggers a clean rollback if raised inside a {@code @Transactional} boundary.</p>
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ProductNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a detail message.
     *
     * @param message the detail message (e.g., the missing product id)
     */
    public ProductNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a detail message and underlying cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause (may be {@code null})
     */
    public ProductNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
