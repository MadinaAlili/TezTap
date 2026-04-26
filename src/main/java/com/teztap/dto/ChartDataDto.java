package com.teztap.dto;

public record ChartDataDto<K, V>(
        K label,
        V value
) {}
