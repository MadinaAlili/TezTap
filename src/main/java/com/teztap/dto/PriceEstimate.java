package com.teztap.dto;

import java.math.BigDecimal;

/**
 * Full pricing breakdown returned to the client.
 * The frontend can display each component for transparency (Uber style).
 */
public record PriceEstimate(

        // ── Component charges ──────────────────────────────────
        BigDecimal baseFare,
        BigDecimal distanceCharge,
        BigDecimal timeCharge,

        // ── Applied multipliers (1.00 means no effect) ─────────
        BigDecimal peakHourMultiplier,
        BigDecimal surgeMultiplier,
        BigDecimal weatherMultiplier,
        String     weatherCondition,    // "CLEAR" | "RAIN" | "SNOW" | "STORM"

        // ── Totals ─────────────────────────────────────────────
        BigDecimal subtotal,            // after all multipliers, before service fee
        BigDecimal serviceFee,
        BigDecimal totalFare,           // what the customer pays

        // ── Route info ─────────────────────────────────────────
        double distanceKm,
        double durationMinutes,
        String encodedPolyline,

        // ── Human-readable summary ─────────────────────────────
        String breakdown
) {}