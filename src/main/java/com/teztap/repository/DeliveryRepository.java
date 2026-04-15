package com.teztap.repository;

import com.teztap.model.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {
    boolean existsByOrderId(Long orderId);

    @Query("SELECT d.order.user.username FROM Delivery d WHERE d.id = :deliveryId")
    Optional<String> findCustomerUsernameByDeliveryId(@Param("deliveryId") Long deliveryId);
}
