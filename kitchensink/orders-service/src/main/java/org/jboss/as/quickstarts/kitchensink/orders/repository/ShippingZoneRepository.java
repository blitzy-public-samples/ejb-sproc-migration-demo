package org.jboss.as.quickstarts.kitchensink.orders.repository;

import java.util.List;
import org.jboss.as.quickstarts.kitchensink.orders.model.ShippingZone;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShippingZoneRepository extends JpaRepository<ShippingZone, Long> {

    List<ShippingZone> findAllByOrderByIdAsc();
}
