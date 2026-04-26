package com.teztap.dto;

import java.math.BigDecimal;
import java.util.List;

public record MarketCartResponse(
        Long marketId,
        String marketName,
        List<CartItemResponse> items,
        BigDecimal marketTotal
) {}
