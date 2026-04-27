package com.teztap.service;

import com.teztap.controller.websocket.WebSocketRoutes;
import com.teztap.dto.DeliveryStatusResponse;
import com.teztap.kafka.kafkaEventDto.OrderCourierAssignedEvent;
import com.teztap.kafka.kafkaEventDto.OrderCourierUnassignedEvent;
import com.teztap.model.Delivery;
import com.teztap.model.Order;
import com.teztap.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CourierService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final DeliveryRepository deliveryRepository;
    private final SimpMessagingTemplate messagingTemplate;

    private static final String GEO_KEY = "couriers:geo";

    // Replaced plain Set membership with a per-courier key that has a TTL matching
    // the assignment key (4h). If the app crashes and order-courier-unassigned is
    // never consumed, both keys expire automatically and the courier goes idle.
    // Pattern: courier:active:<username> → "1"  (TTL: 4h)
    private static final String COURIER_ACTIVE_PREFIX     = "courier:active:";

    // Pattern: courier:assignment:<username> → "<deliveryId>:<customerUsername>"  (TTL: 4h)
    private static final String COURIER_ASSIGNMENT_PREFIX = "courier:assignment:";

    private static final Duration ACTIVE_TTL = Duration.ofHours(4);

    public void updateCourierLocation(String courierUsername, Point point) {
        // 1. Update geo position + refresh heartbeat TTL atomically
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] geoKey = GEO_KEY.getBytes();
            byte[] ttlKey = ("courier:ttl:" + courierUsername).getBytes();
            byte[] member = courierUsername.getBytes();
            connection.geoCommands().geoAdd(geoKey, point, member);
            // Heartbeat: 20s. If courier stops sending, they're removed from matching.
            connection.stringCommands().setEx(ttlKey, 20, "online".getBytes());
            return null;
        });

        // 2. Push to customer only if courier has an active delivery
        Boolean isActive = redisTemplate.hasKey(COURIER_ACTIVE_PREFIX + courierUsername);
        if (!Boolean.TRUE.equals(isActive)) return;

        Object rawObj = redisTemplate.opsForValue().get(COURIER_ASSIGNMENT_PREFIX + courierUsername);
        if (rawObj == null) {
            // Assignment key expired but active key still present — inconsistent state.
            // Clean up and stop pushing.
            System.err.println("[CourierService] updateCourierLocation: assignment key missing for active courier '"
                    + courierUsername + "' — cleaning up stale active key");
            redisTemplate.delete(COURIER_ACTIVE_PREFIX + courierUsername);
            return;
        }

        String rawData = rawObj.toString();
        if (!rawData.contains(":")) {
            System.err.println("[CourierService] updateCourierLocation: malformed assignment '" + rawData + "'");
            return;
        }

        String[] parts            = rawData.split(":", 2);
        Long     deliveryId       = Long.valueOf(parts[0]);
        String   customerUsername = parts[1];

        messagingTemplate.convertAndSendToUser(
                customerUsername,
                WebSocketRoutes.CUSTOMER_DELIVERY_STATUS,
                new DeliveryStatusResponse(deliveryId, point.getX(), point.getY(), Order.OrderStatus.ON_THE_WAY)
        );

        System.err.println("[CourierService] Pushed location to customer '" + customerUsername +
                "' for delivery " + deliveryId);
    }

    @KafkaListener(topics = "order-courier-assigned", groupId = "courier-service-assigned")
    public void markCourierAsActive(OrderCourierAssignedEvent event) {
        String customerUsername = deliveryRepository.findCustomerUsernameByDeliveryId(event.deliveryId());
        if (customerUsername == null) {
            System.err.println("[CourierService] markCourierAsActive: no customer for delivery "
                    + event.deliveryId() + " — skipping");
            return;
        }

        String assignmentValue = event.deliveryId() + ":" + customerUsername;

        // Both keys get the same TTL so they expire together if unassigned event is missed
        redisTemplate.opsForValue().set(
                COURIER_ACTIVE_PREFIX + event.courierUsername(), "1", ACTIVE_TTL);
        redisTemplate.opsForValue().set(
                COURIER_ASSIGNMENT_PREFIX + event.courierUsername(), assignmentValue, ACTIVE_TTL);

        System.err.println("[CourierService] markCourierAsActive: courier '" + event.courierUsername()
                + "' → delivery " + event.deliveryId() + ", customer '" + customerUsername + "'");
    }

    @KafkaListener(topics = "order-courier-unassigned", groupId = "courier-service-unassigned")
    public void markCourierAsIdle(OrderCourierUnassignedEvent event) {
        redisTemplate.delete(COURIER_ACTIVE_PREFIX + event.courierUsername());
        redisTemplate.delete(COURIER_ASSIGNMENT_PREFIX + event.courierUsername());
        System.err.println("[CourierService] markCourierAsIdle: courier '" + event.courierUsername() + "' is now idle");
    }

    public Point getCourierLocation(String courierUsername) {
        List<Point> points = redisTemplate.opsForGeo().position(GEO_KEY, courierUsername);
        return (points != null && !points.isEmpty()) ? points.get(0) : null;
    }

    public Point getCourierLocation(Long deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null) {
            System.err.println("[CourierService] getCourierLocation: delivery " + deliveryId + " not found");
            return null;
        }
        String courierUsername = delivery.getCourierUsername();
        if (courierUsername == null) {
            System.err.println("[CourierService] getCourierLocation: delivery " + deliveryId + " has no courier yet");
            return null;
        }
        List<Point> points = redisTemplate.opsForGeo().position(GEO_KEY, courierUsername);
        return (points != null && !points.isEmpty()) ? points.get(0) : null;
    }

    // ── Admin / legacy ──────────────────────────────────────────────────────────

    public void setCourierOnline(Long courierId) {
        redisTemplate.opsForValue().set("couriers:" + courierId + ":online", true, Duration.ofSeconds(20));
    }

    public void setCourierOffline(Long courierId) {
        redisTemplate.opsForValue().getAndDelete("couriers:" + courierId + ":online");
    }

    public void updateCourierGeo(Long courierId, double lat, double lng) {
        redisTemplate.opsForGeo().add("couriers_geo", new Point(lng, lat), courierId.toString());
    }

    public GeoResults<RedisGeoCommands.GeoLocation<Object>> findNearestCouriers(
            double lat, double lng, double radiusKm) {
        return redisTemplate.opsForGeo().search(
                "couriers_geo",
                GeoReference.fromCoordinate(lng, lat),
                new Distance(radiusKm, Metrics.KILOMETERS)
        );
    }
}
