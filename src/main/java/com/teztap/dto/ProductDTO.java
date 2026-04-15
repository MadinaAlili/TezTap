package com.teztap.dto;

import java.math.BigDecimal;

public record ProductDTO(
        Long id,
        String name,
        BigDecimal originalPrice,
        BigDecimal discountPrice,
        BigDecimal discountPercentage,
        String link,
        String imageUrl,
        Long categoryId,
        Long marketId
) {}

