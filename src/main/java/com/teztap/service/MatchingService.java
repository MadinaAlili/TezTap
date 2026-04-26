package com.teztap.service;

import com.teztap.controller.websocket.WebSocketRoutes;
import com.teztap.dto.DeliveryOfferRequest;
import com.teztap.dto.MatchingState;
import com.teztap.kafka.EventPublisher;
import com.teztap.kafka.kafkaEventDto.CourierNotFoundEvent;
import com.teztap.kafka.kafkaEventDto.DeliveryStartedEvent;
import com.teztap.kafka.kafkaEventDto.OrderCourierAssignedEvent;
import com.teztap.model.Delivery;
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
    private static final int SEARCH_RADIUS = 50; // Km
    private static final int COURIER_OFFER_TIMEOUT = 20;

    private final Map<Long, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();


    @KafkaListener(topics = "delivery-started", groupId = "67125")
    public void startMatching(DeliveryStartedEvent event) {
        Delivery delivery = deliveryRepository.findById(event.deliveryId()).orElseThrow();
        // store empty state (no couriers offered yet)
        MatchingState state = new MatchingState(delivery.getId(), List.of(), 0, delivery.getRoute().getStartPoint());
        redisTemplate.opsForValue().set("match:" + delivery.getId(), state, 2, TimeUnit.MINUTES);

        tryNextCourier(event.deliveryId());
    }

    public void tryNextCourier(Long deliveryId) {
        MatchingState state = getState(deliveryId);
        if (state == null) throw new IllegalStateException("No matching state found for deliveryId: " + deliveryId);

        // --- DEBUG: Print Market Location ---
        System.out.println("=====================================================");
        System.out.println("DEBUG [Market Location] -> Y (Lon/Lat?): " + state.marketLocation().getY() +
                ", X (Lon/Lat?): " + state.marketLocation().getX());

        // 1. Fetch nearby couriers dynamically (Added args to include coordinates & distance)
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                stringRedisTemplate.opsForGeo().search(
                        GEO_KEY,
                        GeoReference.fromCoordinate(state.marketLocation().getY(), state.marketLocation().getX()),
                        new Distance(SEARCH_RADIUS, RedisGeoCommands.DistanceUnit.KILOMETERS),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeCoordinates().includeDistance()
                );

        // --- DEBUG: Print Courier Locations ---
        if (results != null && !results.getContent().isEmpty()) {
            System.out.println("DEBUG [Redis Geospatial Search] -> Found " + results.getContent().size() + " couriers in Redis within radius.");
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results.getContent()) {
                String cUsername = result.getContent().getName();
                org.springframework.data.geo.Point pt = result.getContent().getPoint();
                System.out.println("  -> Courier: " + cUsername +
                        " | Distance: " + result.getDistance() +
                        " | Location: X:" + (pt != null ? pt.getX() : "null") +
                        ", Y:" + (pt != null ? pt.getY() : "null"));
            }
        } else {
            System.out.println("DEBUG [Redis Geospatial Search] -> NO COURIERS FOUND IN REDIS GEO_KEY.");
        }
        System.out.println("=====================================================");

        // 2. Filter available + not already offered
        List<String> candidates = results.getContent().stream()
                .map(r -> r.getContent().getName().toString())
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
            // No couriers available - cleanup and publish event once
            System.err.println("===============No available couriers found===============");

            // Cancel any pending timeouts
            cancelTimeout(deliveryId);

            // Clear the matching state from Redis to prevent retries
            cleanupMatchingState(deliveryId);

            // Publish the event ONCE and return gracefully
            eventPublisher.publish(new CourierNotFoundEvent(deliveryId));
            return;
        }

        String courierUsername = candidates.get(0);

        // 3. Update state (add offered courier)
        updateMatchingState(deliveryId, courierUsername);

        // 4. Send offer
        messagingTemplate.convertAndSendToUser(
                courierUsername,
                WebSocketRoutes.COURIER_DELIVERY_OFFER,
                new DeliveryOfferRequest(deliveryId, courierUsername)
        );

        // 5. Schedule timeout
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> handleTimeout(deliveryId, courierUsername, state.marketLocation()),
                Instant.now().plusSeconds(COURIER_OFFER_TIMEOUT)
        );

        // store so we can cancel later
        timeouts.put(deliveryId, future);
    }

    private void handleTimeout(Long deliveryId, String courier, Point marketLocation) {
        if (orderService.isWaitingForCourier(deliveryId)) {
            tryNextCourier(deliveryId);
        }
    }

    private MatchingState getState(Long deliveryId) {
        return (MatchingState) redisTemplate.opsForValue().get("match:" + deliveryId);
    }

    // Updates match state and add courier to a previously offered couriers list
    private void updateMatchingState(Long orderId, String courier) {
        MatchingState state = getState(orderId);
        if (state == null) return;

        List<String> updated = new java.util.ArrayList<>(state.offeredCouriers());
        updated.add(courier);

        MatchingState newState = new MatchingState(orderId, updated, updated.size(), state.marketLocation());
        redisTemplate.opsForValue().set("match:" + orderId, newState, 2, TimeUnit.MINUTES);
    }

    private void cancelTimeout(Long orderId) {
        ScheduledFuture<?> future = timeouts.remove(orderId);
        if (future != null) {
            future.cancel(false); // stop scheduled timeout
        }
    }

    private void cleanupMatchingState(Long deliveryId) {
        redisTemplate.delete("match:" + deliveryId);
    }

    public void acceptOrder(Long deliveryId, String courierUsername) {
        // stop timeout
        cancelTimeout(deliveryId);

        // cleanup matching state since match is complete
        cleanupMatchingState(deliveryId);

        // send courier assigned event
        eventPublisher.publish(new OrderCourierAssignedEvent(deliveryId, courierUsername));

        // notify courier (optional)

        // notify customer (optional)
        // to be implemented
    }

    public void rejectOrder(Long orderId, String courier) {
        // if delivery offer rejected by courier we stop timeout and try the next courier immediately
        cancelTimeout(orderId);
        tryNextCourier(orderId);
    }
}