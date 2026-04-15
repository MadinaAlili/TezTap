package com.teztap.dto;

import java.time.LocalDateTime;
import java.util.List;

public record MarketWithBranchesDto(
        Long id,
        String name,
        String baseUrl,
        String categoryScrapingBaseUrl,
        Boolean active,
        LocalDateTime created,
        List<MarketBranchDto> branches
) {}
