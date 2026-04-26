package com.teztap.dto;

import java.util.Date;
import java.util.List;

// Category DTO that includes the nested products payload
public record CategoryWithProductsDto(
        Long id,
        String name,
        String url,
        Date created,
        List<ProductDto> products // Assuming ProductDto is already created
) {}
