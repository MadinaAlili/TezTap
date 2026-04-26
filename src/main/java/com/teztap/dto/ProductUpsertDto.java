package com.teztap.dto;

import com.teztap.model.Category;
import com.teztap.model.Market;

import java.math.BigDecimal;

public record ProductUpsertDto(
        String name,
        String link,
        BigDecimal originalPrice,
        BigDecimal discountPrice,
        BigDecimal discountPercentage,
        String imageUrl,
        Category category,
        Market market
) {}
