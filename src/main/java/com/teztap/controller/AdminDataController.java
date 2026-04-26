package com.teztap.controller;

import com.teztap.dto.AdminDashboardPayloadDto;
import com.teztap.dto.ChartDataDto;
import com.teztap.dto.DashboardSummaryDto;
import com.teztap.dto.TopCategoryDto;
import com.teztap.service.AdminDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/admin/analytics")
@RequiredArgsConstructor
public class AdminDataController {

    private final AdminDataService adminDataService;

    // ==========================================
    // 1. DASHBOARD SUMMARY
    // ==========================================

    // Get the top-level numbers for dashboard cards
    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryDto> getDashboardSummary() {
        return ResponseEntity.ok(adminDataService.getDashboardSummary());
    }

    // Optional: Get EVERYTHING in one massive payload (Great for fast initial page loads)
    @GetMapping("/full-dashboard")
    public ResponseEntity<AdminDashboardPayloadDto> getFullDashboardData() {
        return ResponseEntity.ok(adminDataService.getFullDashboardData());
    }

    // ==========================================
    // 2. CHART / GRAPH DATA
    // ==========================================

    // Pie Chart Data: How many orders are Pending vs Delivered vs Cancelled?
    @GetMapping("/charts/orders-by-status")
    public ResponseEntity<List<ChartDataDto<String, Long>>> getOrdersByStatus() {
        return ResponseEntity.ok(adminDataService.getOrdersGroupedByStatus());
    }

    // Line Chart Data: Revenue per month for the current year
    @GetMapping("/charts/revenue-monthly")
    public ResponseEntity<List<ChartDataDto<String, BigDecimal>>> getMonthlyRevenue() {
        return ResponseEntity.ok(adminDataService.getMonthlyRevenue());
    }

    // Bar Chart Data: How many orders were placed each day for the last 7 days?
    @GetMapping("/charts/orders-last-week")
    public ResponseEntity<List<ChartDataDto<String, Long>>> getOrdersForLastWeek() {
        return ResponseEntity.ok(adminDataService.getDailyOrdersForLastWeek());
    }

    // ==========================================
    // 3. LEADERBOARDS & RANKINGS
    // ==========================================

    // Which markets are generating the most orders?
    @GetMapping("/top/markets")
    public ResponseEntity<List<ChartDataDto<String, Long>>> getTopMarketsByOrderVolume(
            @RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok(adminDataService.getTopMarkets(limit));
    }

    // Which categories have the most products?
    @GetMapping("/top/categories")
    public ResponseEntity<List<TopCategoryDto>> getCategoriesWithMostProducts(
            @RequestParam(defaultValue = "5") int limit) {

        return ResponseEntity.ok(adminDataService.getTopCategoriesByProductCount(limit));
    }
}