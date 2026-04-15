package com.teztap.service;

import com.teztap.controller.websocket.WebSocketRoutes;
import com.teztap.dto.DeliveryStatusResponse;
import com.teztap.kafka.kafkaEventDto.OrderCourierAssignedEvent;
import com.teztap.model.Courier;
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

    private final String GEO_KEY = "couriers:geo";
    private static final String ACTIVE_COURIERS_SET = "couriers:active:set";
    private static final String COURIER_ASSIGNMENT_PREFIX = "courier:assignment:";

    // updates courier live location, stores location in redis
    public void updateCourierLocation(String courierUsername, Point point) {
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] geoKey = GEO_KEY.getBytes();
            byte[] ttlKey = ("courier:ttl:" + courierUsername).getBytes();
            byte[] member = courierUsername.getBytes();

            // 1. Update Location in GeoSet
            connection.geoCommands().geoAdd(geoKey, point, member);

            // 2. Refresh Heartbeat TTL (20 seconds)
            connection.stringCommands().setEx(ttlKey, 20, "online".getBytes());

            return null; // Pipeline returns results as a list separately
        });

        // Send the location to the client via WebSocket if courier has active delivery
        if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(ACTIVE_COURIERS_SET, courierUsername))) {

            // 2. Get the composite string: "123:john_doe"
            String rawData = redisTemplate.opsForValue().get(COURIER_ASSIGNMENT_PREFIX + courierUsername).toString();

            if (rawData != null && rawData.contains(":")) {
                String[] parts = rawData.split(":");
                Long deliveryId = Long.valueOf(parts[0]);
                String customerUsername = parts[1];

                // 3. Send live update to the specific customer
                // No DB check required!
                messagingTemplate.convertAndSendToUser(
                        customerUsername,
                        WebSocketRoutes.CUSTOMER_DELIVERY_STATUS,
                        new DeliveryStatusResponse(deliveryId, point.getX(), point.getY(), Order.OrderStatus.ON_THE_WAY)
                );
            }
        }

    }

    @KafkaListener(topics = "order-courier-assigned", groupId = "order-courier-assigned-group")
    public void markCourierAsActive(OrderCourierAssignedEvent event) {
        // 1. Add to the global SET for fast O(1) membership checks
        redisTemplate.opsForSet().add(ACTIVE_COURIERS_SET, event.courierUsername());
        String customerUsername = deliveryRepository.findCustomerUsernameByDeliveryId(event.deliveryId()).orElseThrow();

        // 2. Store the specific delivery mapping with a TTL (e.g., 4 hours)
        // This acts as a safety net so couriers don't stay "active" forever if a crash occurs
        // We store customerUsername as well to ensure we can identify the delivery later without checking database
        String value = event.deliveryId() + ":" + customerUsername;
        redisTemplate.opsForValue().set(
                COURIER_ASSIGNMENT_PREFIX + event.courierUsername(),
                value,
                Duration.ofHours(4)
        );
    }

    @KafkaListener(topics = "order-courier-unassigned", groupId = "order-courier-unassigned-group")
    public void markCourierAsIdle(String courierUsername) {
        redisTemplate.opsForSet().remove(ACTIVE_COURIERS_SET, courierUsername);
        redisTemplate.delete(COURIER_ASSIGNMENT_PREFIX + courierUsername);
    }

    public Point getCourierLocation(String courierUsername) {
        List<Point> points = redisTemplate.opsForGeo().position(GEO_KEY, courierUsername);
        return !points.isEmpty() ? points.get(0) : null;
    }

    public Point getCourierLocation(Long orderId) {
        Delivery delivery = deliveryRepository.findById(orderId).get();
        Courier deliveryCourier = delivery.getCourier();
        List<Point> points = redisTemplate.opsForGeo().position(GEO_KEY, deliveryCourier.getUser().getUsername());
        return !points.isEmpty() ? points.get(0) : null;
    }

    public void setCourierOnline(Long courierId) {
        redisTemplate.opsForValue().set("couriers:" + courierId + ":online", true, Duration.ofSeconds(20));
    }

    public void setCourierOffline(Long courierId) {
        redisTemplate.opsForValue().getAndDelete("couriers:" + courierId + ":online");
    }

    public void updateCourierGeo(Long courierId, double lat, double lng) {
        redisTemplate.opsForGeo().add("couriers_geo", new Point(lng, lat), courierId.toString());
    }

    public GeoResults<RedisGeoCommands.GeoLocation<Object>> findNearestCouriers(double lat, double lng, double radiusKm) {

        return redisTemplate.opsForGeo().search("couriers_geo", GeoReference.fromCoordinate(lng, lat), new Distance(radiusKm, Metrics.KILOMETERS));
    }


}
