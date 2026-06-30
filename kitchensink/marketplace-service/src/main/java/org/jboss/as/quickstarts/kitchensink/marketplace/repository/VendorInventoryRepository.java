package org.jboss.as.quickstarts.kitchensink.marketplace.repository;

import java.util.List;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.VendorInventory;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.VendorInventoryId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorInventoryRepository extends JpaRepository<VendorInventory, VendorInventoryId> {

    List<VendorInventory> findByIdProductId(Long productId);
}
