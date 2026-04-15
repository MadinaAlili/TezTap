package com.teztap.dto;

import com.teztap.model.TimeRange;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

public record MarketBranchDto(
        Long id,
        String name,
        String googlePlaceId,
        String description,
        AddressDto address,
        String phoneNumber,
        Map<DayOfWeek, List<TimeRange>> openingHours,
        boolean open24_7,
        String plusCode,
        boolean active
) {}
