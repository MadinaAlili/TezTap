package com.teztap.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
    Long productId,
    String productName,
    String imageUrl,
    Integer quantity,
    BigDecimal priceAtPurchase,
    BigDecimal subtotal
) {}
