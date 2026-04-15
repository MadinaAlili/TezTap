package com.teztap.service;


import com.teztap.dto.PriceEstimate;
import com.teztap.dto.PriceRequest;
import com.teztap.dto.RouteInfo;
import com.teztap.model.PricingConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;

/**
 * Uber-style pricing engine.
 *
 * Formula (applied in order):
 *
 *   1. base         = baseFare + (pricePerKm × distKm) + (pricePerMin × durMin)
 *   2. peak         = base × peakHourMultiplier          (1.0 outside peak hours)
 *   3. surge        = peak × surgeMultiplier             (demand-based + admin floor)
 *   4. weather      = surge × weatherMultiplier          (1.0 when clear)
 *   5. subtotal     = max(weather, minimumFare)
 *   6. serviceFee   = subtotal × serviceFeePercent / 100
 *   7. total        = subtotal + serviceFee
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingService {

    private final PricingConfigService configService;
    private final RoutingService       routingService;
    private final WeatherService       weatherService;
    private final SurgeService         surgeService;

    public PriceEstimate estimate(PriceRequest req) {
        PricingConfig config = configService.getActiveConfig();

        // ── 1. Routing ───────────────────────────────────────────────────────
        RouteInfo route = routingService.getRoute(
                req.marketLat().doubleValue(),   req.marketLng().doubleValue(),
                req.customerLat().doubleValue(), req.customerLng().doubleValue()
        );

        // ── 2. Base charges ──────────────────────────────────────────────────
        BigDecimal distanceCharge = config.getPricePerKm()
                .multiply(BigDecimal.valueOf(route.distanceKm()))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal timeCharge = config.getPricePerMinute()
                .multiply(BigDecimal.valueOf(route.durationMinutes()))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal running = config.getBaseFare()
                .add(distanceCharge)
                .add(timeCharge);

        // ── 3. Peak-hour multiplier ──────────────────────────────────────────
        BigDecimal peakMultiplier = isPeakHour(config)
                ? config.getPeakHourMultiplier()
                : BigDecimal.ONE;

        running = running.multiply(peakMultiplier).setScale(2, RoundingMode.HALF_UP);

        // ── 4. Surge multiplier (demand-based + admin floor, capped) ────────
        BigDecimal surgeMultiplier = computeSurgeMultiplier(config);
        running = running.multiply(surgeMultiplier).setScale(2, RoundingMode.HALF_UP);

        // ── 5. Weather multiplier ────────────────────────────────────────────
        WeatherService.WeatherCondition weatherCondition = weatherService.getCurrentCondition(
                req.marketLat().doubleValue(), req.marketLng().doubleValue()
        );
        BigDecimal weatherMultiplier = resolveWeatherMultiplier(config, weatherCondition);
        running = running.multiply(weatherMultiplier).setScale(2, RoundingMode.HALF_UP);

        // ── 6. Minimum fare floor ────────────────────────────────────────────
        BigDecimal subtotal = running.max(config.getMinimumFare());

        // ── 7. Service fee ───────────────────────────────────────────────────
        BigDecimal serviceFee = subtotal
                .multiply(config.getServiceFeePercent())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        BigDecimal totalFare = subtotal.add(serviceFee);

        log.info("Price estimate: {} AZN | dist={:.2f}km dur={:.1f}min peak=x{} surge=x{} weather={}(x{})",
                totalFare, route.distanceKm(), route.durationMinutes(),
                peakMultiplier, surgeMultiplier, weatherCondition, weatherMultiplier);

        return new PriceEstimate(
                config.getBaseFare(),
                distanceCharge,
                timeCharge,
                peakMultiplier,
                surgeMultiplier,
                weatherMultiplier,
                weatherCondition.name(),
                subtotal,
                serviceFee,
                totalFare,
                route.distanceKm(),
                route.durationMinutes(),
                route.encodedPolyline(),
                buildBreakdown(config.getBaseFare(), distanceCharge, timeCharge,
                        peakMultiplier, surgeMultiplier, weatherMultiplier, weatherCondition.name(),
                        serviceFee, totalFare)
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isPeakHour(PricingConfig config) {
        int hour    = LocalTime.now().getHour();
        boolean am  = hour >= config.getPeakMorningStart() && hour < config.getPeakMorningEnd();
        boolean pm  = hour >= config.getPeakEveningStart() && hour < config.getPeakEveningEnd();
        return am || pm;
    }

    /**
     * Surge = max(baseSurgeMultiplier, baseSurge + demandBoost), capped at maxSurgeMultiplier.
     *
     * demandBoost = (demandRatio - threshold) × step   (only when ratio > threshold)
     *
     * Example with defaults:
     *   threshold=2.0, step=0.10, baseSurge=1.0
     *   ratio=3.5 → boost = (3.5 - 2.0) × 0.10 = 0.15 → surge = 1.15  (capped at max 3.0)
     */
    private BigDecimal computeSurgeMultiplier(PricingConfig config) {
        BigDecimal demandRatio = surgeService.getDemandRatio();
        BigDecimal surge       = config.getBaseSurgeMultiplier();

        if (demandRatio.compareTo(config.getDemandSurgeThreshold()) > 0) {
            BigDecimal excess      = demandRatio.subtract(config.getDemandSurgeThreshold());
            BigDecimal demandBoost = excess.multiply(config.getDemandSurgeStep());
            surge = surge.add(demandBoost);
            log.debug("Demand surge: ratio={}, excess={}, boost={}, total={}",
                    demandRatio, excess, demandBoost, surge);
        }

        return surge.min(config.getMaxSurgeMultiplier()).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveWeatherMultiplier(PricingConfig config, WeatherService.WeatherCondition cond) {
        return switch (cond) {
            case STORM -> config.getWeatherStormMultiplier();
            case SNOW  -> config.getWeatherSnowMultiplier();
            case RAIN  -> config.getWeatherRainMultiplier();
            case CLEAR -> BigDecimal.ONE;
        };
    }

    private String buildBreakdown(
            BigDecimal base, BigDecimal dist, BigDecimal time,
            BigDecimal peak, BigDecimal surge, BigDecimal weather, String weatherName,
            BigDecimal fee, BigDecimal total
    ) {
        return String.format(
                "%.2f base + %.2f distance + %.2f time → ×%.2f peak, ×%.2f surge, ×%.2f %s weather | %.2f service fee → %.2f AZN total",
                base, dist, time, peak, surge, weather, weatherName, fee, total
        );
    }
}