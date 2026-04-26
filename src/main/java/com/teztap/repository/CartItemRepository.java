package com.teztap.repository;

import com.teztap.model.CartItem;
import com.teztap.model.Product;
import com.teztap.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findByUser(User user);
    Optional<CartItem> findByUserAndProduct(User user, Product product);
    void deleteByUser(User user);

    @Query("SELECT c FROM CartItem c JOIN FETCH c.product WHERE c.id IN :ids")
    List<CartItem> findAllByIdWithProduct(@Param("ids") List<Long> ids);

    List<CartItem> findAllByUserId(Long userId);

    @Modifying
    @Query("DELETE FROM CartItem c WHERE c.id IN :ids AND c.user = :user")
    void deleteByIdInAndUser(@Param("ids") List<Long> ids, @Param("user") User user);
}
