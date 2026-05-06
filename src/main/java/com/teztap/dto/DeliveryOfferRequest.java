package com.teztap.dto;

import java.math.BigDecimal;

/**
 * Sent to the courier via WebSocket (/user/queue/delivery) when a delivery
 * offer is made. Includes pricing so the courier can see the fare before
 * accepting or rejecting.
 */
public record DeliveryOfferRequest(
        Long deliveryId,
        String courier,

        // ── Route info ─────────────────────────────────────────────────────
        double distanceKm,
        double durationMinutes,

        // ── Fare breakdown ─────────────────────────────────────────────────
        BigDecimal totalFare,       // what the customer pays
        BigDecimal baseFare,
        BigDecimal distanceCharge,
        BigDecimal timeCharge,
        BigDecimal peakHourMultiplier,
        BigDecimal surgeMultiplier,
        BigDecimal weatherMultiplier,
        String weatherCondition,    // "CLEAR" | "RAIN" | "SNOW" | "STORM"
        BigDecimal serviceFee,

        // ── Pickup / dropoff ───────────────────────────────────────────────
        String pickupAddress,       // market branch full address
        String dropoffAddress       // customer delivery address
) {}
