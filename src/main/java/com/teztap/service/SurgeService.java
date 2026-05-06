package com.teztap.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
@RequiredArgsConstructor
public class SurgeService {

    // Use RedisTemplate<String, Object> to match the rest of the app
    private final RedisTemplate<String, Object> redisTemplate;

    @FunctionalInterface
    public interface ActiveOrderCounter {
        long count();
    }

    private final ActiveOrderCounter activeOrderCounter;

    private static final String COURIERS_GEO_KEY = "couriers:geo";

    public BigDecimal getDemandRatio() {
        long onlineCouriers = getOnlineCourierCount();
        long activeOrders   = activeOrderCounter.count();

        log.debug("Surge check: {} active orders, {} online couriers", activeOrders, onlineCouriers);

        if (onlineCouriers == 0) {
            return BigDecimal.valueOf(10.0);
        }

        return BigDecimal.valueOf(activeOrders)
                .divide(BigDecimal.valueOf(onlineCouriers), 4, RoundingMode.HALF_UP);
    }

    private long getOnlineCourierCount() {
        try {
            Long size = redisTemplate.opsForZSet().size(COURIERS_GEO_KEY);
            return size != null ? size : 0L;
        } catch (Exception ex) {
            log.warn("Redis unavailable for courier count, defaulting to 1: {}", ex.getMessage());
            return 1L;
        }
    }
}
