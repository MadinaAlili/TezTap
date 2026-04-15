package com.teztap.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates the live demand ratio:
 *
 *   demandRatio = activeOrders / onlineCouriers
 *
 * Online couriers are counted from your existing Redis geo set ("couriers:geo").
 * Active orders come from your order repository — implement ActiveOrderCounter
 * (a simple functional interface) as a Spring bean in your order module.
 *
 * The ratio is then used by PricingService to compute a demand-based surge on
 * top of the admin's base surge multiplier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurgeService {

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Implement this in your order module and expose it as a Spring bean.
     * Example:
     *
     *   @Bean
     *   public ActiveOrderCounter activeOrderCounter(OrderRepository repo) {
     *       return () -> repo.countByStatusIn(List.of("PENDING", "ACCEPTED", "IN_PROGRESS"));
     *   }
     */

    @FunctionalInterface
    public interface ActiveOrderCounter {
        long count();
    }

    private final ActiveOrderCounter activeOrderCounter;

    /** Key used in your existing location service (must match). */
    private static final String COURIERS_GEO_KEY = "couriers:geo";

    /**
     * Returns the demand ratio (orders per courier).
     * If Redis is down, returns 1.0 (neutral — no demand surge).
     * If there are zero couriers online, returns a high ratio to trigger max surge.
     */
    public BigDecimal getDemandRatio() {
        long onlineCouriers = getOnlineCourierCount();
        long activeOrders   = activeOrderCounter.count();

        log.debug("Surge check: {} active orders, {} online couriers", activeOrders, onlineCouriers);

        if (onlineCouriers == 0) {
            // No couriers available — return a high ratio so surge is applied
            return BigDecimal.valueOf(10.0);
        }

        return BigDecimal.valueOf(activeOrders)
                .divide(BigDecimal.valueOf(onlineCouriers), 4, RoundingMode.HALF_UP);
    }

    private long getOnlineCourierCount() {
        try {
            // ZCARD on your geo set — members with expired TTL keys still linger
            // briefly but heartbeat keys expire every 10s so lag is minimal.
            Long size = redisTemplate.opsForZSet().size(COURIERS_GEO_KEY);
            return size != null ? size : 0L;
        } catch (Exception ex) {
            log.warn("Redis unavailable for courier count, defaulting to 1: {}", ex.getMessage());
            return 1L;   // treat as 1 courier to avoid division-by-zero surge
        }
    }
}