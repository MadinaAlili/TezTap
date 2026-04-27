package com.teztap.service;

import com.teztap.dto.MatchingState;
import com.teztap.model.Delivery;
import com.teztap.model.Order;
import com.teztap.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Recovers in-progress courier matching after an app restart.
 *
 * Problem: MatchingService.timeouts is a ConcurrentHashMap — it lives only in
 * memory. If the app restarts while a delivery is waiting for a courier to
 * accept/reject, the scheduled timeout is lost. The delivery stays in
 * WAITING_FOR_COURIER forever with a stale match:<deliveryId> key in Redis
 * and no timeout to advance it.
 *
 * Fix: on startup, scan all match:* keys in Redis, verify the delivery is
 * still WAITING_FOR_COURIER in the DB, and if so call tryNextCourier() to
 * re-offer it immediately. The next courier in the candidate list will be
 * offered the delivery and a fresh timeout will be scheduled.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingRecoveryService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DeliveryRepository deliveryRepository;
    private final MatchingService matchingService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInProgressMatching() {
        log.info("[MatchingRecovery] Scanning for orphaned matching states…");

        Set<String> matchKeys = redisTemplate.keys("match:*");
        if (matchKeys == null || matchKeys.isEmpty()) {
            log.info("[MatchingRecovery] No orphaned matching states found.");
            return;
        }

        log.info("[MatchingRecovery] Found {} match key(s): {}", matchKeys.size(), matchKeys);

        for (String key : matchKeys) {
            try {
                MatchingState state = (MatchingState) redisTemplate.opsForValue().get(key);
                if (state == null) continue;

                Long deliveryId = state.deliveryId();

                Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
                if (delivery == null) {
                    log.warn("[MatchingRecovery] Delivery {} not found — deleting orphaned key {}", deliveryId, key);
                    redisTemplate.delete(key);
                    continue;
                }

                Order.OrderStatus parentStatus = delivery.getSubOrder().getParentOrder().getStatus();

                if (parentStatus == Order.OrderStatus.WAITING_FOR_COURIER) {
                    log.info("[MatchingRecovery] Delivery {} still WAITING_FOR_COURIER — resuming matching.", deliveryId);
                    // tryNextCourier will re-offer to the next available courier
                    // (couriers already offered are stored in state.offeredCouriers())
                    // and schedule a fresh timeout.
                    matchingService.tryNextCourier(deliveryId);
                } else {
                    log.info("[MatchingRecovery] Delivery {} parent order is {} — deleting stale match key.",
                            deliveryId, parentStatus);
                    redisTemplate.delete(key);
                }

            } catch (Exception e) {
                log.error("[MatchingRecovery] Error processing key {}: {}", key, e.getMessage(), e);
            }
        }

        log.info("[MatchingRecovery] Done.");
    }
}
