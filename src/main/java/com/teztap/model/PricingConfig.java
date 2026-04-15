package com.teztap.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Admin-tunable pricing configuration.
 * Only one config can be active at a time (enforced by PricingConfigService).
 * All multipliers stack multiplicatively in PricingService.
 */
@Entity
@Table(name = "pricing_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable label, e.g. "default", "ramadan-promo", "winter-2025" */
    @Column(nullable = false, unique = true)
    private String name;

    /** Only one config can be active; activate via /admin/pricing/configs/{id}/activate */
    @Column(nullable = false)
    private boolean active;

    // ── Base Fares ──────────────────────────────────────────────────────────

    /** Fixed charge per order regardless of distance (e.g. 1.50 AZN) */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal baseFare;

    /** Charge per kilometer (e.g. 0.85 AZN/km) */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal pricePerKm;

    /** Charge per minute of travel (e.g. 0.20 AZN/min) */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal pricePerMinute;

    /** Floor price — customer never pays less than this (e.g. 3.00 AZN) */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal minimumFare;

    /** Service fee percentage added on top of the multiplied fare (e.g. 10.00 = 10%) */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal serviceFeePercent;

    // ── Peak Hours (stored as hour-of-day 0–23) ─────────────────────────────

    /** Morning peak window start, inclusive (e.g. 7 → 07:00) */
    @Column(nullable = false)
    private int peakMorningStart;

    /** Morning peak window end, exclusive (e.g. 10 → before 10:00) */
    @Column(nullable = false)
    private int peakMorningEnd;

    /** Evening peak window start, inclusive (e.g. 17 → 17:00) */
    @Column(nullable = false)
    private int peakEveningStart;

    /** Evening peak window end, exclusive (e.g. 21 → before 21:00) */
    @Column(nullable = false)
    private int peakEveningEnd;

    /** Multiplier applied during peak windows (e.g. 1.30 = +30%) */
    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal peakHourMultiplier;

    // ── Surge / Demand ───────────────────────────────────────────────────────

    /**
     * Admin-set manual surge floor. Set to 1.0 for no manual surge.
     * Demand-based surge is added on top of this if ratio exceeds threshold.
     */
    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal baseSurgeMultiplier;

    /** Hard cap on combined surge (e.g. 3.00 — customer never pays more than 3× base) */
    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal maxSurgeMultiplier;

    /**
     * activeOrders / onlineCouriers ratio at which demand surge kicks in.
     * Below this threshold: only baseSurgeMultiplier applies.
     * e.g. 2.0 means surge starts when there are 2+ orders per courier.
     */
    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal demandSurgeThreshold;

    /**
     * How much to add to surge per unit above the threshold.
     * e.g. 0.10 means +0.10× surge for each extra order per courier above threshold.
     */
    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal demandSurgeStep;

    // ── Weather Multipliers ──────────────────────────────────────────────────

    /** Multiplier during rain/drizzle (e.g. 1.20) */
    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal weatherRainMultiplier;

    /** Multiplier during snow (e.g. 1.30) */
    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal weatherSnowMultiplier;

    /** Multiplier during thunderstorm (e.g. 1.50) */
    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal weatherStormMultiplier;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}