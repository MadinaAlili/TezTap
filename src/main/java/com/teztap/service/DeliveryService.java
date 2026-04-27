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
    private final OrderRepository orderRepository;

    @Transactional
    @KafkaListener(topics = "order-payment-completed", groupId = "14214")
    public void initiateDeliveries(OrderPaymentCompletedEvent event) {
        log.info("initiateDeliveries: received for orderId={}", event.orderId());

        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + event.orderId()));

        log.info("initiateDeliveries: order found, subOrders count={}", order.getSubOrders().size());

        for (SubOrder subOrder : order.getSubOrders()) {
            if (deliveryRepository.existsBySubOrderId(subOrder.getId())) {
                log.info("initiateDeliveries: delivery already exists for subOrderId={} — skipping", subOrder.getId());
                continue;
            }

            try {
                MarketBranch branch = subOrder.getMarketBranch();

                double branchLat = branch.getAddress().getLocation().getY();
                double branchLng = branch.getAddress().getLocation().getX();
                double destLat   = order.getOrderAddress().getLocation().getY();
                double destLng   = order.getOrderAddress().getLocation().getX();

                log.info("initiateDeliveries: fetching route for subOrderId={} | branch({},{}) → dest({},{})",
                        subOrder.getId(), branchLat, branchLng, destLat, destLng);

                RouteInfo route = routingService.getRoute(branchLat, branchLng, destLat, destLng);

                log.info("initiateDeliveries: route fetched, polyline length={}",
                        route != null && route.encodedPolyline() != null ? route.encodedPolyline().length() : "null");

                Delivery delivery = new Delivery();
                delivery.setSubOrder(subOrder);
                delivery.setRoute(GeometryUtils.decodePolylineToLineString(route.encodedPolyline()));
                delivery.setDelivered(false);
                delivery.setNote(order.getDeliveryNote());

                Delivery savedDelivery = deliveryRepository.save(delivery);
                log.info("initiateDeliveries: delivery saved id={} for subOrderId={}", savedDelivery.getId(), subOrder.getId());

                eventPublisher.publish(new DeliveryStartedEvent(order.getId(), savedDelivery.getId()));

            } catch (Exception e) {
                log.error("initiateDeliveries: FAILED for subOrderId={} — {}", subOrder.getId(), e.getMessage(), e);
                throw e;
            }
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

        subOrder.setStatus(Order.OrderStatus.CANCELLED_COURIER_NOT_FOUND);

        boolean allBranchesCancelled = parentOrder.getSubOrders().stream()
                .allMatch(so -> so.getStatus() == Order.OrderStatus.CANCELLED_COURIER_NOT_FOUND);

        if (allBranchesCancelled) {
            parentOrder.setStatus(Order.OrderStatus.CANCELLED_COURIER_NOT_FOUND);
        }

        orderRepository.save(parentOrder);
        deliveryRepository.save(delivery);

        BigDecimal amountToRefund = subOrder.getItems().stream()
                .map(item -> item.getPriceAtPurchase().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        eventPublisher.publish(new OrderRefundRequestedEvent(
                parentOrder.getId(),
                "Partial Cancellation: No couriers available for one of your locations.",
                amountToRefund
        ));
    }

    @Transactional
    public void finishDelivery(DeliveryFinishedResponse response, String courierUsername) {
        log.info("Finishing delivery {} by courier {}", response.deliveryId(), courierUsername);

        Delivery delivery = deliveryRepository.findById(response.deliveryId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Delivery not found: " + response.deliveryId()));

        SubOrder subOrder = delivery.getSubOrder();
        Order parentOrder = subOrder.getParentOrder();

        // Idempotency
        if (subOrder.getStatus() == Order.OrderStatus.DELIVERED || delivery.isDelivered()) {
            log.warn("Delivery {} already delivered — skipping.", delivery.getId());
            return;
        }

        if (subOrder.getStatus() == Order.OrderStatus.CANCELLED ||
                subOrder.getStatus() == Order.OrderStatus.CANCELLED_COURIER_NOT_FOUND) {
            throw new IllegalStateException("Cannot complete a cancelled delivery.");
        }

        // Security check
        String assignedCourier = delivery.getCourierUsername();
        if (assignedCourier == null) {
            log.error("Delivery {} has no courier assigned — rejecting finish by '{}'.",
                    delivery.getId(), courierUsername);
            throw new SecurityException("Delivery " + delivery.getId() + " has no assigned courier.");
        }
        if (!assignedCourier.equals(courierUsername)) {
            log.warn("Security: courier '{}' tried to finish delivery assigned to '{}'.",
                    courierUsername, assignedCourier);
            throw new SecurityException("You are not authorized to complete this delivery.");
        }

        // Update this delivery and its subOrder
        subOrder.setStatus(Order.OrderStatus.DELIVERED);
        delivery.setDelivered(true);
        delivery.setDeliveryTime(LocalDateTime.now());

        // Save delivery first so the DB is consistent before we check sibling subOrders
        deliveryRepository.save(delivery);

        // Re-fetch all sibling deliveries fresh from DB to check if entire order is done.
        // DO NOT use parentOrder.getSubOrders() here — it is a stale Hibernate collection
        // that doesn't reflect the subOrder.setStatus() we just did on siblings in previous
        // calls, which is why isEntireOrderFinished was always evaluating to false.
        long totalSubOrders = deliveryRepository.countByParentOrderId(parentOrder.getId());
        long finishedSubOrders = deliveryRepository.countFinishedByParentOrderId(parentOrder.getId());

        log.info("finishDelivery: delivery {} done. SubOrders total={}, finished={}",
                delivery.getId(), totalSubOrders, finishedSubOrders);

        if (finishedSubOrders >= totalSubOrders) {
            parentOrder.setStatus(Order.OrderStatus.DELIVERED);
            orderRepository.save(parentOrder);
            log.info("All sub-orders done. Parent Order {} → DELIVERED.", parentOrder.getId());
        }

        log.info("Completed delivery {} (SubOrder {}).", delivery.getId(), subOrder.getId());

        // Publish delivered event (no LocalDateTime — Jackson can't serialize it without JSR310)
        eventPublisher.publish(new OrderDeliveredEvent(
                parentOrder.getId(),
                delivery.getId(),
                courierUsername
        ));

        // Fix: publish order-courier-unassigned so CourierService.markCourierAsIdle()
        // clears the Redis active set and assignment key, stopping location pushes.
        eventPublisher.publish(new OrderCourierUnassignedEvent(courierUsername));
        log.info("finishDelivery: published order-courier-unassigned for courier '{}'", courierUsername);
    }
}
