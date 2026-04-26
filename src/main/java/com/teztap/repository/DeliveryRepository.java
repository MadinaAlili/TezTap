package com.teztap.repository;

import com.teztap.model.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    @Query("SELECT d.subOrder.parentOrder.user.username FROM Delivery d WHERE d.id = :deliveryId")
    String findCustomerUsernameByDeliveryId(@Param("deliveryId") Long deliveryId);

    boolean existsBySubOrderId(Long subOrderId);
}
