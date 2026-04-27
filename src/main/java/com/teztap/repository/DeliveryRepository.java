package com.teztap.repository;

import com.teztap.model.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    @Query("SELECT d.subOrder.parentOrder.user.username FROM Delivery d WHERE d.id = :deliveryId")
    String findCustomerUsernameByDeliveryId(@Param("deliveryId") Long deliveryId);

    boolean existsBySubOrderId(Long subOrderId);

    @Query("SELECT d.subOrder.parentOrder.id FROM Delivery d WHERE d.id = :deliveryId")
    Optional<Long> findOrderIdByDeliveryId(@Param("deliveryId") Long deliveryId);

    @Query("SELECT d.subOrder.parentOrder.status FROM Delivery d WHERE d.id = :deliveryId")
    Optional<String> findParentOrderStatusByDeliveryId(@Param("deliveryId") Long deliveryId);

    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.subOrder.parentOrder.id = :orderId")
    long countByParentOrderId(@Param("orderId") Long orderId);

    @Query("""
        SELECT COUNT(d) FROM Delivery d
        WHERE d.subOrder.parentOrder.id = :orderId
        AND d.subOrder.status IN (
            com.teztap.model.Order$OrderStatus.DELIVERED,
            com.teztap.model.Order$OrderStatus.CANCELLED,
            com.teztap.model.Order$OrderStatus.CANCELLED_COURIER_NOT_FOUND
        )
    """)
    long countFinishedByParentOrderId(@Param("orderId") Long orderId);

    @Query("""
        SELECT CASE WHEN (
            d.delivered = true
            OR d.subOrder.status = com.teztap.model.Order$OrderStatus.DELIVERED
            OR d.subOrder.status = com.teztap.model.Order$OrderStatus.CANCELLED
            OR d.subOrder.status = com.teztap.model.Order$OrderStatus.CANCELLED_COURIER_NOT_FOUND
        ) THEN true ELSE false END
        FROM Delivery d WHERE d.id = :deliveryId
    """)
    Boolean isDeliveryTerminal(@Param("deliveryId") Long deliveryId);

    // All deliveries assigned to a courier, newest first
    @Query("""
        SELECT d FROM Delivery d
        WHERE d.courierUsername = :courierUsername
        ORDER BY d.deliveryTime DESC NULLS LAST
    """)
    List<Delivery> findAllByCourierUsername(@Param("courierUsername") String courierUsername);

    // Current active delivery for a courier (not yet delivered)
    @Query("""
        SELECT d FROM Delivery d
        WHERE d.courierUsername = :courierUsername
        AND d.delivered = false
    """)
    Optional<Delivery> findActiveDeliveryByCourierUsername(@Param("courierUsername") String courierUsername);
}
