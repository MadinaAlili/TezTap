package com.teztap.dto;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(
        List<MarketCartResponse> markets,
        BigDecimal total
) {}