package com.teztap.repository;

import com.teztap.model.Market;
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

    Optional<Product> findByNameAndMarket(String name, Market market);

    List<Product> findAllByMarketAndNameIn(Market market, Collection<String> names);

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

    // For Endpoint #5: Find products by category name
    Page<Product> findByCategoryNameIgnoreCase(String categoryName, Pageable pageable);

    // For Endpoint #6: Count total products for a specific category
    long countByCategoryId(Long categoryId);

    @Query(
            value = """
            SELECT * FROM products p 
            WHERE p.id != :sourceId 
            AND similarity(p.name, :sourceName) > :threshold 
            ORDER BY similarity(p.name, :sourceName) DESC
            """,
            countQuery = """
            SELECT count(*) FROM products p 
            WHERE p.id != :sourceId 
            AND similarity(p.name, :sourceName) > :threshold
            """,
            nativeQuery = true
    )
    Page<Product> findSimilarProducts(
            @Param("sourceName") String sourceName,
            @Param("sourceId") Long sourceId,
            @Param("threshold") double threshold,
            Pageable pageable
    );

    @Query("SELECT p FROM Product p WHERE " +
            "(:keyword IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))) AND " +
            "(:marketName IS NULL OR LOWER(p.market.name) = LOWER(CAST(:marketName AS string))) AND " +
            "(:categoryId IS NULL OR p.category.id = :categoryId) AND " +
            "(:onlyDiscounted = false OR (p.discountPercentage IS NOT NULL AND p.discountPercentage > 0))")
    Page<Product> findWithDynamicFilters(
            @Param("keyword") String keyword,
            @Param("marketName") String marketName,
            @Param("categoryId") Long categoryId,
            @Param("onlyDiscounted") boolean onlyDiscounted,
            Pageable pageable
    );
}
