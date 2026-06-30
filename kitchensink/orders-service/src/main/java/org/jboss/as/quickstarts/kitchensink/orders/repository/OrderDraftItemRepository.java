package org.jboss.as.quickstarts.kitchensink.orders.repository;

import java.util.List;

import org.jboss.as.quickstarts.kitchensink.orders.model.OrderDraftItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link OrderDraftItem} (the {@code order_draft_items} cart table),
 * owned by orders-service.
 *
 * <p>CRUD (including {@code save}, used by add-to-cart) is inherited from {@link JpaRepository}.
 * The finder and the two bulk-delete operations below replace the monolith's hand-written JPQL
 * in {@code OrderService} and the {@code process_order} cart-clear.</p>
 */
public interface OrderDraftItemRepository extends JpaRepository<OrderDraftItem, Long> {

    /**
     * Cart contents for a member.
     *
     * <p>Replaces the monolith cart read
     * ({@code "SELECT odi FROM OrderDraftItem odi WHERE odi.memberId = :memberId"})
     * as a Spring Data derived query. Consumed by {@code OrderService} preview/submit orchestration.</p>
     */
    List<OrderDraftItem> findByMemberId(Long memberId);

    /**
     * Removes a single product line from a member's cart.
     *
     * <p>Faithful bulk-delete reimplementation of the monolith {@code OrderService.removeFromCart}
     * JPQL ({@code "DELETE FROM OrderDraftItem odi WHERE odi.memberId = :memberId AND odi.productId = :productId"}).
     * Returns the number of rows deleted. The caller MUST run this inside a transaction
     * ({@code @Transactional} on the service method).</p>
     */
    @Modifying
    @Query("DELETE FROM OrderDraftItem odi WHERE odi.memberId = :memberId AND odi.productId = :productId")
    int deleteByMemberIdAndProductId(@Param("memberId") Long memberId, @Param("productId") Long productId);

    /**
     * Clears a member's entire cart (used at order-submit time).
     *
     * <p>Faithful reimplementation of the {@code process_order} cart-clear
     * ({@code "DELETE FROM order_draft_items WHERE member_id = p_member_id"}, db/02_stored_procedures.sql L286).
     * Returns the number of rows deleted. The caller MUST run this inside a transaction.</p>
     */
    @Modifying
    @Query("DELETE FROM OrderDraftItem odi WHERE odi.memberId = :memberId")
    int deleteByMemberId(@Param("memberId") Long memberId);
}
