package com.teztap.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CourierDeliveryDto(
        Long deliveryId,
        Long parentOrderId,
        Long subOrderId,
        String subOrderStatus,
        boolean delivered,
        LocalDateTime deliveryTime,
        String marketName,
        String deliveryNote,

        // ── Addresses ──────────────────────────────────────────────────────
        AddressDto pickupAddress,
        AddressDto dropoffAddress,

        // ── Pricing ────────────────────────────────────────────────────────
        // Locked in at delivery-creation time — reflects the fare the customer
        // was charged for this specific delivery leg.
        BigDecimal deliveryFee,
        Double distanceKm,
        Double durationMinutes
) {}
