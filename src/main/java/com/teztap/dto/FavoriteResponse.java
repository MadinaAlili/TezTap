package com.teztap.dto;

import java.math.BigDecimal;
import java.util.Date;

public record FavoriteResponse(
        Long favoriteId,
        Long productId,
        String productName,
        String imageUrl,
        BigDecimal originalPrice,
        BigDecimal discountPrice,
        String marketName,
        Date dateAdded
) {}