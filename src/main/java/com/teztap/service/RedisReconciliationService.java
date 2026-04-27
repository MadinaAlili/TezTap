package com.teztap.service;

import com.teztap.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisReconciliationService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DeliveryRepository deliveryRepository;

    private static final String COURIER_ACTIVE_PREFIX     = "courier:active:";
    private static final String COURIER_ASSIGNMENT_PREFIX = "courier:assignment:";

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("[Reconciliation] Starting Redis ↔ DB reconciliation…");

        Set<String> activeKeys = redisTemplate.keys(COURIER_ACTIVE_PREFIX + "*");
        if (activeKeys == null || activeKeys.isEmpty()) {
            log.info("[Reconciliation] No active courier keys found — nothing to reconcile.");
            return;
        }

        log.info("[Reconciliation] Found {} active courier key(s).", activeKeys.size());

        for (String activeKey : activeKeys) {
            String courierUsername = activeKey.replace(COURIER_ACTIVE_PREFIX, "");
            try {
                reconcileCourier(courierUsername);
            } catch (Exception e) {
                log.error("[Reconciliation] Failed for courier '{}': {}", courierUsername, e.getMessage(), e);
            }
        }

        log.info("[Reconciliation] Done.");
    }

    private void reconcileCourier(String courierUsername) {
        String assignmentKey = COURIER_ASSIGNMENT_PREFIX + courierUsername;
        Object rawObj = redisTemplate.opsForValue().get(assignmentKey);

        if (rawObj == null) {
            log.warn("[Reconciliation] Courier '{}' active but no assignment key — evicting.", courierUsername);
            evict(courierUsername);
            return;
        }

        String rawData = rawObj.toString();
        if (!rawData.contains(":")) {
            log.warn("[Reconciliation] Malformed assignment '{}' for courier '{}' — evicting.", rawData, courierUsername);
            evict(courierUsername);
            return;
        }

        Long deliveryId = Long.valueOf(rawData.split(":", 2)[0]);

        // Use a JPQL query that fetches the terminal status in a single DB call
        // with no lazy loading — avoids LazyInitializationException entirely.
        // @Transactional on a self-called method is ignored by Spring's proxy,
        // so the safest approach is to not rely on any Hibernate session here at all.
        Boolean isTerminal = deliveryRepository.isDeliveryTerminal(deliveryId);

        if (isTerminal == null) {
            log.warn("[Reconciliation] Delivery {} not in DB for courier '{}' — evicting.", deliveryId, courierUsername);
            evict(courierUsername);
            return;
        }

        if (isTerminal) {
            log.info("[Reconciliation] Delivery {} is terminal — evicting courier '{}'.", deliveryId, courierUsername);
            evict(courierUsername);
        } else {
            log.info("[Reconciliation] Delivery {} still active — keeping courier '{}'.", deliveryId, courierUsername);
        }
    }

    private void evict(String courierUsername) {
        redisTemplate.delete(COURIER_ACTIVE_PREFIX + courierUsername);
        redisTemplate.delete(COURIER_ASSIGNMENT_PREFIX + courierUsername);
        log.info("[Reconciliation] Evicted courier '{}' from Redis.", courierUsername);
    }
}
