package org.jboss.as.quickstarts.kitchensink.marketplace.repository;

import java.util.List;
import java.util.Optional;

import org.jboss.as.quickstarts.kitchensink.marketplace.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Product} entities (marketplace catalog/pricing context).
 *
 * <p>Replaces the monolith's hand-rolled {@code @ApplicationScoped} +
 * {@code @Inject EntityManager} repository. Spring Data auto-implements this interface at
 * runtime; no {@code @Repository} stereotype and no {@code EntityManager} are required.
 * The inherited {@code findById(Long)} supersedes the monolith's {@code em.find(Product.class, id)},
 * and {@code findAll()} remains available alongside the name-ordered variant below.</p>
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Looks up a product by its unique SKU. Returns {@link Optional} rather than the entity
     * directly so an absent SKU yields an empty Optional instead of the monolith's
     * {@code NoResultException} (former JPQL {@code getSingleResult()} behavior).
     */
    Optional<Product> findBySku(String sku);

    /**
     * Returns all products in a category ordered by name ascending — preserves the monolith's
     * {@code "SELECT p FROM Product p WHERE p.category = :category ORDER BY p.name"}.
     */
    List<Product> findByCategoryOrderByNameAsc(String category);

    /**
     * Returns all products ordered by name ascending — preserves the monolith's
     * {@code "SELECT p FROM Product p ORDER BY p.name"} sort parity.
     */
    List<Product> findAllByOrderByNameAsc();
}
