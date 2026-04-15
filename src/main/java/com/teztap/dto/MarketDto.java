package com.teztap.dto;

import java.time.LocalDateTime;

public record MarketDto(
        Long id,
        String name,
        String baseUrl,
        String categoryScrapingBaseUrl,
        Boolean active,
        LocalDateTime created
) {}

