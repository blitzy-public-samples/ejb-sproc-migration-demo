package org.jboss.as.quickstarts.kitchensink.data;

import java.util.List;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.jboss.as.quickstarts.kitchensink.model.OrderDraftItem;

/**
 * Spring Data JPA repository for {@link OrderDraftItem} (the draft shopping cart).
 *
 * New in the Spring Boot migration: replaces the EntityManager.persist + JPQL DELETE that OrderService
 * previously used for the draft cart, satisfying the binding "no EntityManager anywhere" rule.
 * - findByMemberId: cart contents (used for the addToCart upsert check, the orchestrateOrder line loop,
 *   and the submitOrder draft clear).
 * - lockDraftsByMemberId: PESSIMISTIC_WRITE (SELECT ... FOR UPDATE) snapshot of the cart, taken at the
 *   start of OrderService.submitOrder to serialize concurrent submits of the same cart (QA Issue 4).
 * - deleteByMemberIdAndProductId: removeFromCart (delete a single cart line).
 * - deleteByMemberId: submitOrder cart clear after the order is persisted (mirrors process_order L286).
 * New draft rows are inserted via the inherited save(...); quantity updates via save(...) of a loaded row.
 */
public interface OrderDraftItemRepository extends JpaRepository<OrderDraftItem, Long> {

    List<OrderDraftItem> findByMemberId(Long memberId);

    /**
     * Acquires a PESSIMISTIC_WRITE lock (PostgreSQL {@code SELECT ... FOR UPDATE}) on the member's draft
     * rows and returns them.
     *
     * <p>CONCURRENCY (QA Issue 4 — "at most one order should be created"): {@code OrderDraftItem} is
     * intentionally unversioned (it mirrors an external schema with no optimistic-lock column), so two
     * concurrent {@code submitOrder} calls for the same cart could otherwise both read the drafts and each
     * persist an order (a double-order), or race on the post-commit draft delete and surface a raw
     * {@code StaleObjectStateException} as HTTP 500. {@link org.jboss.as.quickstarts.kitchensink.service.OrderService#submitOrder}
     * calls this method first, inside its transaction, to take a row lock on the cart. The first submit
     * holds the lock until it commits (after deleting the drafts); a second concurrent submit blocks here,
     * then — under READ COMMITTED — re-evaluates and sees the now-deleted rows as gone, so it proceeds with
     * an empty cart and is rejected with a controlled empty-cart 400 by {@code orchestrateOrder}. This
     * guarantees at most one order per cart and a graceful 4xx for the loser instead of a 500/double-order.
     * The {@code OptimisticLockingFailureException -> 409} mapping in {@code RestExceptionHandler} remains
     * as defense-in-depth.</p>
     *
     * @param memberId  the owning member's id
     * @return          the locked draft rows (possibly empty)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from OrderDraftItem d where d.memberId = :memberId")
    List<OrderDraftItem> lockDraftsByMemberId(@Param("memberId") Long memberId);

    @Transactional
    @Modifying
    void deleteByMemberIdAndProductId(Long memberId, Long productId);

    @Transactional
    @Modifying
    void deleteByMemberId(Long memberId);
}
