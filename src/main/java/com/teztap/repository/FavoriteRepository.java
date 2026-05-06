package com.teztap.repository;

import com.teztap.model.Favorite;
import com.teztap.model.Product;
import com.teztap.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, Long> {
    Page<Favorite> findByUserOrderByCreatedDesc(User user, Pageable pageable);
    boolean existsByUserAndProduct(User user, Product product);
    Optional<Favorite> findByUserAndProduct(User user, Product product);

    @Query("SELECT f.product.id FROM Favorite f WHERE f.user = :user AND f.product.id IN :productIds")
    Set<Long> findFavoritedProductIdsByUserAndProductIds(
            @Param("user") User user,
            @Param("productIds") List<Long> productIds
    );
}
