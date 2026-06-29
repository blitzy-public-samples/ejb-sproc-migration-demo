package org.jboss.as.quickstarts.kitchensink.data;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import org.jboss.as.quickstarts.kitchensink.model.Product;

/**
 * Spring Data JPA repository for {@link Product}.
 *
 * Migrated from a Jakarta EE CDI bean (@ApplicationScoped + @Inject EntityManager + JPQL) to a
 * Spring Data interface. CRUD inherited from JpaRepository; custom finders below are derived queries.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    // Was findBySku(...) via JPQL getSingleResult. Now derived, returns Optional<Product>.
    Optional<Product> findBySku(String sku);

    // Was findByCategory(...) via JPQL "... WHERE p.category = :category ORDER BY p.name".
    List<Product> findByCategoryOrderByNameAsc(String category);

    // Was findAll() via JPQL "SELECT p FROM Product p ORDER BY p.name" (name-ordered).
    // JpaRepository.findAll() exists but is UNORDERED, so this explicit name-ordered finder is kept
    // to preserve the prior ordering contract for the products listing endpoint.
    List<Product> findAllByOrderByNameAsc();
}
