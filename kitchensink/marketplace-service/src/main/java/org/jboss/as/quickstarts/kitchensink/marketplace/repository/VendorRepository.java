package org.jboss.as.quickstarts.kitchensink.marketplace.repository;

import java.util.List;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorRepository extends JpaRepository<Vendor, Long> {

    List<Vendor> findAllByOrderByFulfillmentRatingDesc();
}
