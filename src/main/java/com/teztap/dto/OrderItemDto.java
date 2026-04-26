package com.teztap.dto;

import java.math.BigDecimal;

public record OrderItemDto(
        Long productId,
        String productName,    // Assuming your Product entity has a name
        String imageUrl,       // Assuming your Product entity has an image
        Integer quantity,
        BigDecimal priceAtPurchase
) {}
