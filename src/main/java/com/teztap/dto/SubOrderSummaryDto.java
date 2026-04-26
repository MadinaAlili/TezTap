package com.teztap.dto;

public record SubOrderSummaryDto(
        Long subOrderId,
        String marketName,
        String subOrderStatus
) {}
