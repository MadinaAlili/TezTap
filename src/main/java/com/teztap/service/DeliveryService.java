package com.teztap.service;

import com.teztap.dto.DeliveryFinishedResponse;
import com.teztap.dto.RouteInfo;
import com.teztap.kafka.EventPublisher;
import com.teztap.kafka.kafkaEventDto.*;
import com.teztap.model.Delivery;
import com.teztap.model.MarketBranch;
import com.teztap.model.Order;
import com.teztap.model.SubOrder;
import com.teztap.repository.DeliveryRepository;
import com.teztap.repository.OrderRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {
    private final RoutingService routingService;
    private final DeliveryRepository deliveryRepository;
    private final EventPublisher eventPublisher;

    // Assuming a modular monolith structure where DeliveryService
    // can access the Order DB directly to fetch missing details.
    private final OrderRepository orderRepository;

    @Transactional
    @KafkaListener(topics = "order-payment-completed", groupId = "14214")
    public void initiateDeliveries(OrderPaymentCompletedEvent event) {
        log.info("Initiating deliveries for Parent Order ID: {}", event.orderId());

        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));

        for (SubOrder subOrder : order.getSubOrders()) {
            if (deliveryRepository.existsBySubOrderId(subOrder.getId())) continue;

            MarketBranch branch = subOrder.getMarketBranch();
            RouteInfo route = routingService.getRoute(
                    branch.getAddress().getLocation().getY(), branch.getAddress().getLocation().getX(),
                    order.getOrderAddress().getLocation().getY(), order.getOrderAddress().getLocation().getX()
            );

            Delivery delivery = new Delivery();
            delivery.setSubOrder(subOrder);
            delivery.setRoute(GeometryUtils.decodePolylineToLineString(route.encodedPolyline()));
            delivery.setDelivered(false);
            delivery.setNote(order.getDeliveryNote());

            Delivery savedDelivery = deliveryRepository.save(delivery);

            //  USING YOUR EXACT EVENT HERE
            eventPublisher.publish(new DeliveryStartedEvent(order.getId(), savedDelivery.getId()));
        }
    }

    @Transactional
    @KafkaListener(topics = "courier-not-found", groupId = "61924")
    public void handleCourierNotFound(CourierNotFoundEvent event) {
        log.warn("No courier found for Delivery ID: {}", event.deliveryId());

        Delivery delivery = deliveryRepository.findById(event.deliveryId())
                .orElseThrow(() -> new EntityNotFoundException("Delivery not found"));

        SubOrder subOrder = delivery.getSubOrder();
        Order parentOrder = subOrder.getParentOrder();

        if (subOrder.getStatus() == Order.OrderStatus.CANCELLED_COURIER_NOT_FOUND) return;

        // 1. Cancel this specific branch
        subOrder.setStatus(Order.OrderStatus.CANCELLED_COURIER_NOT_FOUND);

        // 2. Check if the whole order is now dead
        boolean allBranchesCancelled = parentOrder.getSubOrders().stream()
                .allMatch(so -> so.getStatus() == Order.OrderStatus.CANCELLED_COURIER_NOT_FOUND);

        if (allBranchesCancelled) {
            parentOrder.setStatus(Order.OrderStatus.CANCELLED_COURIER_NOT_FOUND);
        }

        orderRepository.save(parentOrder);
        deliveryRepository.save(delivery);

        // 3. Calculate exactly how much this failed branch cost
        BigDecimal amountToRefund = subOrder.getItems().stream()
                .map(item -> item.getPriceAtPurchase().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        //  USING YOUR EXISTING EVENT
        eventPublisher.publish(new OrderRefundRequestedEvent(
                parentOrder.getId(),
                "Partial Cancellation: No couriers available for one of your locations.",
                amountToRefund
        ));
    }

    @Transactional
    public void finishDelivery(DeliveryFinishedResponse response, String courierUsername) {
        log.info("Finishing delivery for Delivery ID: {} by Courier: {}", response.deliveryId(), courierUsername);

        // 1. Fetch the Delivery record
        Delivery delivery = deliveryRepository.findById(response.deliveryId())
                .orElseThrow(() -> new EntityNotFoundException("Delivery not found with ID: " + response.deliveryId()));

        // 2. Navigate to the new architecture's entities
        SubOrder subOrder = delivery.getSubOrder();
        Order parentOrder = subOrder.getParentOrder();

        // 3. Idempotency Check: Check the SubOrder, not the Parent Order
        if (subOrder.getStatus() == Order.OrderStatus.DELIVERED || delivery.isDelivered()) {
            log.warn("Delivery {} is already marked as delivered. Skipping duplicate request.", delivery.getId());
            return;
        }

        if (subOrder.getStatus() == Order.OrderStatus.CANCELLED ||
                subOrder.getStatus() == Order.OrderStatus.CANCELLED_COURIER_NOT_FOUND) {
            log.error("Courier {} attempted to finish delivery {}, but it was already cancelled.", courierUsername, delivery.getId());
            throw new IllegalStateException("Cannot complete a cancelled delivery.");
        }

        // 4. Security Check
        if (delivery.getCourier() != null && !delivery.getCourier().getUser().getUsername().equals(courierUsername)) {
            log.warn("Security Alert: Courier {} is attempting to finish delivery assigned to {}",
                    courierUsername, delivery.getCourier().getUser().getUsername());
            throw new SecurityException("You are not authorized to complete this delivery.");
        }

        // 5. Update the Delivery and SubOrder State
        subOrder.setStatus(Order.OrderStatus.DELIVERED);
        delivery.setDelivered(true);
        delivery.setDeliveryTime(LocalDateTime.now());

        // 6. Parent Order Resolution
        // Check if ALL branches in this order are now either Delivered or Cancelled.
        // If they are, the entire parent order is officially finished.
        boolean isEntireOrderFinished = parentOrder.getSubOrders().stream()
                .allMatch(so -> so.getStatus() == Order.OrderStatus.DELIVERED ||
                        so.getStatus() == Order.OrderStatus.CANCELLED ||
                        so.getStatus() == Order.OrderStatus.CANCELLED_COURIER_NOT_FOUND);

        if (isEntireOrderFinished) {
            parentOrder.setStatus(Order.OrderStatus.DELIVERED);
            log.info("All sub-orders completed. Parent Order ID: {} is now fully DELIVERED.", parentOrder.getId());
        }

        // Save everything
        orderRepository.save(parentOrder); // Cascade will save the subOrder
        deliveryRepository.save(delivery);

        log.info("Successfully completed Delivery ID: {} (SubOrder ID: {})", delivery.getId(), subOrder.getId());

        // 7. USING YOUR EXACT EVENT
        // The Payment service can use the deliveryId to pay this specific courier.
        // The Notification service can use the deliveryId to find the branch name and text:
        // "Your McDonald's items have arrived!"
        eventPublisher.publish(new OrderDeliveredEvent(
                parentOrder.getId(),
                delivery.getId(),
                courierUsername,
                delivery.getDeliveryTime()
        ));
    }
}
