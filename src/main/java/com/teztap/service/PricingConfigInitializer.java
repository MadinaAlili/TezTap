package com.teztap.config;

import com.teztap.model.PricingConfig;
import com.teztap.repository.PricingConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Inserts a sensible default pricing config on first startup if none exists.
 * Admin can update or replace it via POST /api/admin/pricing/configs.
 * Safe to run on every restart — skips if any config already exists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PricingConfigInitializer {

    private final PricingConfigRepository repository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void insertDefaultIfEmpty() {
        if (repository.count() > 0) {
            log.info("[PricingConfig] Config already exists — skipping default insert.");
            return;
        }

        PricingConfig defaults = PricingConfig.builder()
                .name("default")
                .active(true)

                // ── Base fares ────────────────────────────────────────────
                // 1.50 AZN flat + 0.85 AZN/km + 0.20 AZN/min
                // Minimum the customer ever pays: 3.00 AZN
                // Service fee: 10% on top of the fare
                .baseFare(new BigDecimal("1.50"))
                .pricePerKm(new BigDecimal("0.85"))
                .pricePerMinute(new BigDecimal("0.20"))
                .minimumFare(new BigDecimal("3.00"))
                .serviceFeePercent(new BigDecimal("10.00"))

                // ── Peak hours ────────────────────────────────────────────
                // Morning: 07:00 – 10:00   Evening: 17:00 – 21:00
                // +30% during peak windows
                .peakMorningStart(7)
                .peakMorningEnd(10)
                .peakEveningStart(17)
                .peakEveningEnd(21)
                .peakHourMultiplier(new BigDecimal("1.30"))

                // ── Surge / demand ────────────────────────────────────────
                // No manual surge by default (1.0 = neutral)
                // Demand surge kicks in when ratio > 2.0 orders/courier
                // Each extra order/courier above threshold adds +0.10×
                // Hard cap at 3.0× so customers are never shocked
                .baseSurgeMultiplier(new BigDecimal("1.00"))
                .maxSurgeMultiplier(new BigDecimal("3.00"))
                .demandSurgeThreshold(new BigDecimal("2.00"))
                .demandSurgeStep(new BigDecimal("0.10"))

                // ── Weather multipliers ───────────────────────────────────
                .weatherRainMultiplier(new BigDecimal("1.20"))
                .weatherSnowMultiplier(new BigDecimal("1.30"))
                .weatherStormMultiplier(new BigDecimal("1.50"))

                .build();

        repository.save(defaults);
        log.info("[PricingConfig] Default pricing config inserted and activated.");
    }
}
