package org.jboss.as.quickstarts.kitchensink.marketplace.repository;

import java.util.List;
import java.util.Optional;
import org.jboss.as.quickstarts.kitchensink.marketplace.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    List<Product> findAllByOrderByNameAsc();

    List<Product> findByCategoryOrderByNameAsc(String category);
}
