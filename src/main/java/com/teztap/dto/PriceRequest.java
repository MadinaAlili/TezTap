package com.teztap.dto;

import java.math.BigDecimal;

// ─────────────────────────────────────────────────────────────────────────────
// Inbound: what the client sends to get a price estimate
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pass the coordinates of the market (pickup) and the customer (dropoff).
 * If you already have Address objects on the order, extract lat/lng from them
 * and build this record before calling PricingService.estimate().
 */
public record PriceRequest(
        BigDecimal marketLat,
        BigDecimal marketLng,
        BigDecimal customerLat,
        BigDecimal customerLng
) {}