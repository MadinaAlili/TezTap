package com.teztap.dto;

import java.math.BigDecimal;

public record DashboardSummaryDto(
        long totalProducts,
        long totalMarkets,
        long totalCategories,
        long totalOrders,
        long totalCustomers,
        BigDecimal totalRevenue,
        long totalCanceledOrders,
        long totalFailedPayments,
        BigDecimal lostRevenue // Optional, but great for analytics!
) {}
