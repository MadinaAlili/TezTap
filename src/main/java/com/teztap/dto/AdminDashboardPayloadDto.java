package com.teztap.dto;

import java.math.BigDecimal;
import java.util.List;

public record AdminDashboardPayloadDto(
        DashboardSummaryDto summary,
        List<ChartDataDto<String, Long>> ordersByStatusChart, // e.g., "DELIVERED": 150
        List<ChartDataDto<String, BigDecimal>> monthlyRevenueChart, // e.g., "Jan": 500.00
        List<ChartDataDto<String, Long>> topMarketsChart // e.g., "SuperMart": 320 orders
) {}
