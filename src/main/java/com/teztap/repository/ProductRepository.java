package com.teztap.repository;

import com.teztap.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findByLink(String link);

    @Query("SELECT p FROM Product p WHERE p.id IN :ids")
    List<Product> findAllByIds(@Param("ids") List<Long> ids);

    // Find all products that have a discount greater than a specific value (e.g., 0)
    Page<Product> findByDiscountPercentageGreaterThan(BigDecimal discount, Pageable pageable);

    // Find all products for a specific market by its name
    Page<Product> findByMarketNameIgnoreCase(String marketName, Pageable pageable);

    // Find discounted products for a specific market
    Page<Product> findByMarketNameIgnoreCaseAndDiscountPercentageGreaterThan(String marketName, BigDecimal discount, Pageable pageable);

    // Find products by category ID
    Page<Product> findByCategoryId(Long categoryId, Pageable pageable);

    // Search products by name
    Page<Product> findByNameContainingIgnoreCase(String keyword, Pageable pageable);

    List<Product> findAllByLinkIn(Collection<String> links);
}
