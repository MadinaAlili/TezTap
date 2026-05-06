package com.teztap.service;

import com.teztap.dto.DeliveryFinishedResponse;
import com.teztap.dto.PriceEstimate;
import com.teztap.dto.PriceRequest;
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
    private final PricingService pricingService;   // injected for fare calculation
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

                // ── Pricing ───────────────────────────────────────────────────
                // Calculate and lock in the fare at delivery-creation time.
                // We use PricingService which internally calls RoutingService,
                // so we get both the route geometry AND the fare in one call.
                PriceEstimate estimate = calculateFare(
                        branchLat, branchLng, destLat, destLng, subOrder.getId());

                // ── Build delivery ────────────────────────────────────────────
                Delivery delivery = new Delivery();
                delivery.setSubOrder(subOrder);
                delivery.setDelivered(false);
                delivery.setNote(order.getDeliveryNote());

                // Store the encoded polyline as a LineString geometry
                if (estimate.encodedPolyline() != null && !estimate.encodedPolyline().isEmpty()) {
                    delivery.setRoute(GeometryUtils.decodePolylineToLineString(estimate.encodedPolyline()));
                } else {
                    // Routing fell back to Haversine (no polyline) — route stays null,
                    // matching still works since it uses Redis geo, not the route column
                    log.warn("initiateDeliveries: no polyline for subOrderId={} — route stored as null", subOrder.getId());
                }

                // Lock in the fare — survives any future pricing config changes
                delivery.setDeliveryFee(estimate.totalFare());
                delivery.setDistanceKm(estimate.distanceKm());
                delivery.setDurationMinutes(estimate.durationMinutes());

                Delivery savedDelivery = deliveryRepository.save(delivery);
                log.info("initiateDeliveries: delivery saved id={} fee={} AZN dist={}km for subOrderId={}",
                        savedDelivery.getId(), estimate.totalFare(), estimate.distanceKm(), subOrder.getId());

                eventPublisher.publish(new DeliveryStartedEvent(order.getId(), savedDelivery.getId()));
                log.info("initiateDeliveries: DeliveryStartedEvent published for deliveryId={}", savedDelivery.getId());

            } catch (Exception e) {
                log.error("initiateDeliveries: FAILED for subOrderId={} — {}", subOrder.getId(), e.getMessage(), e);
                throw e;
            }
        }
    }

    /**
     * Calculates the fare for a delivery leg.
     * Falls back gracefully — PricingService handles ORS/weather failures internally.
     */
    private PriceEstimate calculateFare(
            double branchLat, double branchLng,
            double destLat, double destLng,
            Long subOrderId) {
        try {
            return pricingService.estimate(new PriceRequest(
                    BigDecimal.valueOf(branchLat), BigDecimal.valueOf(branchLng),
                    BigDecimal.valueOf(destLat),   BigDecimal.valueOf(destLng)
            ));
        } catch (Exception e) {
            // PricingService has internal fallbacks but wrap defensively —
            // a pricing failure should never block delivery creation.
            log.error("initiateDeliveries: pricing failed for subOrderId={} — {}. Storing zero fare.",
                    subOrderId, e.getMessage());
            return zeroPriceEstimate();
        }
    }

    private PriceEstimate zeroPriceEstimate() {
        return new PriceEstimate(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                "CLEAR",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0.0, 0.0, "",
                "Pricing unavailable at time of delivery creation"
        );
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

        if (subOrder.getStatus() == Order.OrderStatus.DELIVERED || delivery.isDelivered()) {
            log.warn("Delivery {} already delivered — skipping.", delivery.getId());
            return;
        }

        if (subOrder.getStatus() == Order.OrderStatus.CANCELLED ||
                subOrder.getStatus() == Order.OrderStatus.CANCELLED_COURIER_NOT_FOUND) {
            throw new IllegalStateException("Cannot complete a cancelled delivery.");
        }

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

        subOrder.setStatus(Order.OrderStatus.DELIVERED);
        delivery.setDelivered(true);
        delivery.setDeliveryTime(LocalDateTime.now());

        deliveryRepository.save(delivery);

        long totalSubOrders    = deliveryRepository.countByParentOrderId(parentOrder.getId());
        long finishedSubOrders = deliveryRepository.countFinishedByParentOrderId(parentOrder.getId());

        log.info("finishDelivery: delivery {} done. SubOrders total={}, finished={}",
                delivery.getId(), totalSubOrders, finishedSubOrders);

        if (finishedSubOrders >= totalSubOrders) {
            parentOrder.setStatus(Order.OrderStatus.DELIVERED);
            orderRepository.save(parentOrder);
            log.info("All sub-orders done. Parent Order {} → DELIVERED.", parentOrder.getId());
        }

        log.info("Completed delivery {} (SubOrder {}).", delivery.getId(), subOrder.getId());

        eventPublisher.publish(new OrderDeliveredEvent(
                parentOrder.getId(),
                delivery.getId(),
                courierUsername
        ));

        eventPublisher.publish(new OrderCourierUnassignedEvent(courierUsername));
        log.info("finishDelivery: published order-courier-unassigned for courier '{}'", courierUsername);
    }
}
