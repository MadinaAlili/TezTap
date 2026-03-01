package com.teztap.dto;

import java.math.BigDecimal;

public record CartItemResponse(
    Long cartItemId,
    Long productId,
    String productName,
    String imageUrl,
    BigDecimal originalPrice,
    BigDecimal discountPrice,
    Integer quantity,
    BigDecimal subtotal
) {}
