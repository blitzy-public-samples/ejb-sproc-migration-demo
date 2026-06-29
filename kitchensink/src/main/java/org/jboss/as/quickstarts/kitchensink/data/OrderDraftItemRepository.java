package org.jboss.as.quickstarts.kitchensink.data;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import org.jboss.as.quickstarts.kitchensink.model.OrderDraftItem;

/**
 * Spring Data JPA repository for {@link OrderDraftItem} (the draft shopping cart).
 *
 * New in the Spring Boot migration: replaces the EntityManager.persist + JPQL DELETE that OrderService
 * previously used for the draft cart, satisfying the binding "no EntityManager anywhere" rule.
 * - findByMemberId: cart contents (used for the addToCart upsert check, the orchestrateOrder line loop,
 *   and the submitOrder draft clear).
 * - deleteByMemberIdAndProductId: removeFromCart (delete a single cart line).
 * - deleteByMemberId: submitOrder cart clear after the order is persisted (mirrors process_order L286).
 * New draft rows are inserted via the inherited save(...); quantity updates via save(...) of a loaded row.
 */
public interface OrderDraftItemRepository extends JpaRepository<OrderDraftItem, Long> {

    List<OrderDraftItem> findByMemberId(Long memberId);

    @Transactional
    @Modifying
    void deleteByMemberIdAndProductId(Long memberId, Long productId);

    @Transactional
    @Modifying
    void deleteByMemberId(Long memberId);
}
