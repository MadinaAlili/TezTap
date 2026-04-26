package com.teztap.dto;

import java.util.Date;

public record CategoryDto(
        Long id,
        String name,
        String url,
        Date created
) {}
