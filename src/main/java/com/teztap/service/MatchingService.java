package com.teztap.service;

import com.teztap.controller.websocket.WebSocketRoutes;
import com.teztap.dto.DeliveryOfferRequest;
import com.teztap.dto.MatchingState;
import com.teztap.kafka.EventPublisher;
import com.teztap.kafka.kafkaEventDto.CourierNotFoundEvent;
import com.teztap.kafka.kafkaEventDto.DeliveryStartedEvent;
import com.teztap.kafka.kafkaEventDto.OrderCourierAssignedEvent;
import com.teztap.model.Delivery;
import com.teztap.model.Order;
import com.teztap.repository.DeliveryRepository;
import com.teztap.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Point;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class MatchingService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final TaskScheduler taskScheduler;
    private final OrderService orderService;
    private final EventPublisher eventPublisher;
    private final OrderRepository orderRepository;
    private final DeliveryRepository deliveryRepository;

    private static final String GEO_KEY = "couriers:geo";
    private static final int SEARCH_RADIUS = 50;
    private static final int COURIER_OFFER_TIMEOUT = 60;

    private final Map<Long, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();

    @KafkaListener(topics = "delivery-started", groupId = "67125")
    public void startMatching(DeliveryStartedEvent event) {
        Delivery delivery = deliveryRepository.findById(event.deliveryId()).orElseThrow();
        MatchingState state = new MatchingState(delivery.getId(), List.of(), 0, delivery.getRoute().getStartPoint());
        redisTemplate.opsForValue().set("match:" + delivery.getId(), state, 2, TimeUnit.MINUTES);
        tryNextCourier(event.deliveryId());
    }

    public void tryNextCourier(Long deliveryId) {
        MatchingState state = getState(deliveryId);
        if (state == null) throw new IllegalStateException("No matching state found for deliveryId: " + deliveryId);

        System.out.println("=====================================================");
        System.out.println("DEBUG [Market Location] -> Y: " + state.marketLocation().getY() +
                ", X: " + state.marketLocation().getX());

        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                stringRedisTemplate.opsForGeo().search(
                        GEO_KEY,
                        GeoReference.fromCoordinate(state.marketLocation().getY(), state.marketLocation().getX()),
                        new Distance(SEARCH_RADIUS, RedisGeoCommands.DistanceUnit.KILOMETERS),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeCoordinates().includeDistance()
                );

        if (results != null && !results.getContent().isEmpty()) {
            System.out.println("DEBUG [Redis Geo] -> Found " + results.getContent().size() + " couriers within radius.");
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results.getContent()) {
                String cUsername = result.getContent().getName();
                org.springframework.data.geo.Point pt = result.getContent().getPoint();
                System.out.println("  -> Courier: " + cUsername +
                        " | Distance: " + result.getDistance() +
                        " | X:" + (pt != null ? pt.getX() : "null") +
                        ", Y:" + (pt != null ? pt.getY() : "null"));
            }
        } else {
            System.out.println("DEBUG [Redis Geo] -> NO COURIERS FOUND.");
        }
        System.out.println("=====================================================");

        List<String> candidates = results.getContent().stream()
                .map(r -> r.getContent().getName())
                .filter(username -> {
                    boolean online = redisTemplate.hasKey("courier:ttl:" + username);
                    if (!online) {
                        System.out.println("DEBUG [Filter] -> Courier " + username + " is OFFLINE (removing from Geo).");
                        redisTemplate.opsForZSet().remove(GEO_KEY, username);
                        return false;
                    }
                    boolean alreadyOffered = state.offeredCouriers().contains(username);
                    if (alreadyOffered) {
                        System.out.println("DEBUG [Filter] -> Courier " + username + " was ALREADY OFFERED.");
                    }
                    return !alreadyOffered;
                })
                .toList();

        if (candidates.isEmpty()) {
            System.err.println("===============No available couriers found===============");
            cancelTimeout(deliveryId);
            cleanupMatchingState(deliveryId);
            eventPublisher.publish(new CourierNotFoundEvent(deliveryId));
            return;
        }

        String courierUsername = candidates.get(0);
        updateMatchingState(deliveryId, courierUsername);

        // Build the offer with pricing data that was locked in at delivery creation
        DeliveryOfferRequest offer = buildOffer(deliveryId, courierUsername);
        messagingTemplate.convertAndSendToUser(
                courierUsername,
                WebSocketRoutes.COURIER_DELIVERY_OFFER,
                offer
        );

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> handleTimeout(deliveryId, courierUsername, state.marketLocation()),
                Instant.now().plusSeconds(COURIER_OFFER_TIMEOUT)
        );
        timeouts.put(deliveryId, future);
    }

    /**
     * Builds the delivery offer sent to the courier via WebSocket.
     * Reads the fare that was locked in when the delivery was created —
     * no re-calculation needed, pricing config may have changed since then.
     */
    private DeliveryOfferRequest buildOffer(Long deliveryId, String courierUsername) {
        Delivery delivery = deliveryRepository.findById(deliveryId).orElseThrow();

        // Pickup address from market branch
        String pickupAddress = "";
        String dropoffAddress = "";
        try {
            pickupAddress = delivery.getSubOrder().getMarketBranch().getAddress().getFullAddress();
            dropoffAddress = delivery.getSubOrder().getParentOrder().getOrderAddress().getFullAddress();
        } catch (Exception e) {
            System.err.println("[MatchingService] buildOffer: could not read addresses for delivery " + deliveryId);
        }

        // Use stored fare — falls back to zero if pricing failed at creation time
        BigDecimal totalFare = delivery.getDeliveryFee() != null
                ? delivery.getDeliveryFee() : BigDecimal.ZERO;
        double distKm   = delivery.getDistanceKm()   != null ? delivery.getDistanceKm()   : 0.0;
        double distMins = delivery.getDurationMinutes() != null ? delivery.getDurationMinutes() : 0.0;

        return new DeliveryOfferRequest(
                deliveryId,
                courierUsername,
                distKm,
                distMins,
                totalFare,
                // We only store totalFare on delivery, not the full breakdown.
                // Send nulls/zeros for breakdown fields — the client shows totalFare.
                BigDecimal.ZERO,  // baseFare
                BigDecimal.ZERO,  // distanceCharge
                BigDecimal.ZERO,  // timeCharge
                BigDecimal.ONE,   // peakHourMultiplier
                BigDecimal.ONE,   // surgeMultiplier
                BigDecimal.ONE,   // weatherMultiplier
                "CLEAR",          // weatherCondition
                BigDecimal.ZERO,  // serviceFee
                pickupAddress,
                dropoffAddress
        );
    }

    private void handleTimeout(Long deliveryId, String courier, Point marketLocation) {
        boolean stillWaiting = deliveryRepository
                .findParentOrderStatusByDeliveryId(deliveryId)
                .map(status -> status.equals(Order.OrderStatus.WAITING_FOR_COURIER.name()))
                .orElse(false);

        if (stillWaiting) {
            tryNextCourier(deliveryId);
        }
    }

    private MatchingState getState(Long deliveryId) {
        return (MatchingState) redisTemplate.opsForValue().get("match:" + deliveryId);
    }

    private void updateMatchingState(Long deliveryId, String courier) {
        MatchingState state = getState(deliveryId);
        if (state == null) return;
        List<String> updated = new java.util.ArrayList<>(state.offeredCouriers());
        updated.add(courier);
        MatchingState newState = new MatchingState(deliveryId, updated, updated.size(), state.marketLocation());
        redisTemplate.opsForValue().set("match:" + deliveryId, newState, 2, TimeUnit.MINUTES);
    }

    private void cancelTimeout(Long deliveryId) {
        ScheduledFuture<?> future = timeouts.remove(deliveryId);
        if (future != null) future.cancel(false);
    }

    private void cleanupMatchingState(Long deliveryId) {
        redisTemplate.delete("match:" + deliveryId);
    }

    @Transactional
    public void acceptOrder(Long deliveryId, String courierUsername) {
        cancelTimeout(deliveryId);
        cleanupMatchingState(deliveryId);

        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalStateException("Delivery not found: " + deliveryId));

        delivery.setCourierUsername(courierUsername);
        delivery.getSubOrder().setCourierId(delivery.getSubOrder().getCourierId());
        deliveryRepository.save(delivery);

        System.err.println("[MatchingService] acceptOrder: courier '" + courierUsername +
                "' assigned to delivery " + deliveryId);

        eventPublisher.publish(new OrderCourierAssignedEvent(deliveryId, courierUsername));
    }

    public void rejectOrder(Long deliveryId, String courier) {
        cancelTimeout(deliveryId);

        MatchingState state = getState(deliveryId);
        if (state == null) {
            System.err.println("[MatchingService] rejectOrder: matching state for delivery "
                    + deliveryId + " no longer exists — publishing CourierNotFoundEvent");
            eventPublisher.publish(new CourierNotFoundEvent(deliveryId));
            return;
        }

        boolean stillWaiting = deliveryRepository
                .findParentOrderStatusByDeliveryId(deliveryId)
                .map(status -> status.equals(Order.OrderStatus.WAITING_FOR_COURIER.name()))
                .orElse(false);

        if (!stillWaiting) {
            System.err.println("[MatchingService] rejectOrder: delivery " + deliveryId
                    + " is no longer WAITING_FOR_COURIER — ignoring late rejection from '" + courier + "'");
            return;
        }

        System.err.println("[MatchingService] rejectOrder: courier '" + courier
                + "' rejected delivery " + deliveryId + " — trying next courier");
        tryNextCourier(deliveryId);
    }
}
