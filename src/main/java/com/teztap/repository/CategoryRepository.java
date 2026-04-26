package com.teztap.repository;

import com.teztap.model.Category;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findByUrl(String url);

    @Query("SELECT DISTINCT c FROM Category c LEFT JOIN FETCH c.products")
    List<Category> findAllWithProducts();

    // Finds all unique categories that have products in the specified market
    @Query("SELECT DISTINCT p.category FROM Product p WHERE p.market.id = :marketId")
    List<Category> findCategoriesByMarketId(@Param("marketId") Long marketId);

    // Standard lookup by name
    Optional<Category> findByName(String name);

    Optional<Category> findFirstByName(String name);

    // Joins Categories and Products, groups by Category ID, counts them, and sorts descending.
    @Query("SELECT c.name, m.name, COUNT(p.id) " +
            "FROM Category c " +
            "LEFT JOIN c.market m " +
            "JOIN c.products p " +
            "GROUP BY c.id, c.name, m.name " +
            "ORDER BY COUNT(p.id) DESC")
    List<Object[]> findTopCategoriesByProductCount(Pageable pageable);

    // Finds all unique categories that have products in a specific market by name (case-insensitive)
    @Query("SELECT DISTINCT p.category FROM Product p WHERE LOWER(p.market.name) = LOWER(:marketName)")
    List<Category> findCategoriesByMarketNameIgnoreCase(@Param("marketName") String marketName);
}
