package com.teztap.dto;

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

        // Pickup: where the courier collects the order (market branch location)
        AddressDto pickupAddress,

        // Dropoff: where the courier delivers to (customer's order address)
        AddressDto dropoffAddress
) {}
