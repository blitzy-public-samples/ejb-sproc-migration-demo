package org.jboss.as.quickstarts.kitchensink.orders.repository;

import java.util.List;
import org.jboss.as.quickstarts.kitchensink.orders.model.OrderDraftItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderDraftItemRepository extends JpaRepository<OrderDraftItem, Long> {

    List<OrderDraftItem> findByMemberId(Long memberId);

    void deleteByMemberIdAndProductId(Long memberId, Long productId);

    void deleteByMemberId(Long memberId);
}
