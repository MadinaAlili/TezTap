package com.teztap.service;

import com.teztap.dto.RouteInfo;
import com.teztap.kafka.EventPublisher;
import com.teztap.kafka.kafkaEventDto.DeliveryStartedEvent;
import com.teztap.kafka.kafkaEventDto.OrderPaymentCompletedEvent;
import com.teztap.model.Delivery;
import com.teztap.model.MarketBranch;
import com.teztap.model.Order;
import com.teztap.repository.DeliveryRepository;
import com.teztap.repository.OrderRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @KafkaListener(topics = "order-payment-completed", groupId = "delivery-service-group")
    public void initiateDelivery(OrderPaymentCompletedEvent event) {
        log.info("Initiating delivery process for Order ID: {}", event.orderId());

        // 1. Fetch the Order (since the event payload is lean)
        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new EntityNotFoundException("Order not found with ID: " + event.orderId()));

        // 2. Idempotency Check: Ensure we haven't already processed this order
        if (deliveryRepository.existsByOrderId(order.getId())) {
            log.warn("Delivery record already exists for Order ID: {}. Skipping duplicate event.", order.getId());
            return;
        }

        // 3. Calculate the PostGIS Route
        // (Assuming your Order entity holds the supermarket and customer coordinates)
        MarketBranch branch = order.getMarketBranch();
        RouteInfo route = routingService.getRoute(
                branch.getAddress().getLocation().getY(), branch.getAddress().getLocation().getX(),
                order.getOrderAddress().getLocation().getY(),order.getOrderAddress().getLocation().getX()
        );

        // 4. Build the Delivery Entity
        Delivery delivery = new Delivery();
        delivery.setOrder(order);
        delivery.setRoute(GeometryUtils.decodePolylineToLineString(route.encodedPolyline()));
        delivery.setDelivered(false);
        delivery.setNote(order.getDeliveryNote()); // Grabbing the "be quick" note
        // delivery.setCourier(null); -> Intentionally left null until matched via Redis Geo
        // delivery.setDeliveryTime(null); -> Set this when the courier actually marks it delivered

        // 5. Persist the Operational Copy
        Delivery savedDelivery = deliveryRepository.save(delivery);
        log.info("Delivery initialized successfully with Delivery ID: {}", delivery.getId());

        // 6. (Optional) Fire the next event for the Redis Dispatcher to find a courier
        // eventPublisher.publishEvent(new DeliveryRequiresCourierEvent(delivery.getId()));
        eventPublisher.publish(new DeliveryStartedEvent(order.getId(), savedDelivery.getId()));
    }
}
