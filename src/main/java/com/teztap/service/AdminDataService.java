package com.teztap.service;

import com.teztap.dto.AdminDashboardPayloadDto;
import com.teztap.dto.ChartDataDto;
import com.teztap.dto.DashboardSummaryDto;
import com.teztap.dto.TopCategoryDto;
import com.teztap.model.Order;
import com.teztap.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AdminDataService {

    private final ProductRepository productRepository;
    private final MarketRepository marketRepository;
    private final CategoryRepository categoryRepository;
    // Assuming you have these repositories:
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    public AdminDataService(ProductRepository productRepository, MarketRepository marketRepository,
                            CategoryRepository categoryRepository, OrderRepository orderRepository,
                            UserRepository userRepository) {
        this.productRepository = productRepository;
        this.marketRepository = marketRepository;
        this.categoryRepository = categoryRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }

    // ==========================================
    // 1. DASHBOARD SUMMARY
    // ==========================================

    public DashboardSummaryDto getDashboardSummary() {
        // 1. Calculate Successful Revenue
        BigDecimal totalRevenue = orderRepository.calculateTotalRevenue();
        if (totalRevenue == null) {
            totalRevenue = BigDecimal.ZERO;
        }

        // 2. Calculate Failed/Canceled Counts (Pass the Enum constants, not Strings)
        long canceledCount = orderRepository.countByStatus(Order.OrderStatus.CANCELLED);
        long failedCount = orderRepository.countByStatus(Order.OrderStatus.PAYMENT_FAILED);

        // 3. Calculate Lost Revenue (Pass a list of Enum constants)
        BigDecimal lostRevenue = orderRepository.calculateTotalRevenueForStatuses(
                List.of(Order.OrderStatus.CANCELLED, Order.OrderStatus.PAYMENT_FAILED)
        );
        if (lostRevenue == null) {
            lostRevenue = BigDecimal.ZERO;
        }

        // 4. Return the updated DTO
        return new DashboardSummaryDto(
                productRepository.count(),
                marketRepository.count(),
                categoryRepository.count(),
                orderRepository.count(),
                userRepository.count(),
                totalRevenue,
                canceledCount,
                failedCount,
                lostRevenue
        );
    }

    public AdminDashboardPayloadDto getFullDashboardData() {
        return new AdminDashboardPayloadDto(
                getDashboardSummary(),
                getOrdersGroupedByStatus(),
                getMonthlyRevenue(),
                getTopMarkets(5)
        );
    }

    // ==========================================
    // 2. CHART / GRAPH DATA
    // ==========================================

    public List<ChartDataDto<String, Long>> getOrdersGroupedByStatus() {
        // The repository returns List<Object[]> where index 0 is String (status) and index 1 is Long (count)
        List<Object[]> results = orderRepository.countOrdersByStatus();

        return results.stream()
                .map(row -> new ChartDataDto<>(row[0].toString(), (Long) row[1]))
                .collect(Collectors.toList());
    }

    public List<ChartDataDto<String, BigDecimal>> getMonthlyRevenue() {
        int currentYear = LocalDate.now().getYear();
        List<Object[]> results = orderRepository.sumRevenueByMonthForYear(currentYear);

        return results.stream()
                .map(row -> new ChartDataDto<>("Month " + row[0].toString(), (BigDecimal) row[1]))
                .collect(Collectors.toList());
    }

    public List<ChartDataDto<String, Long>> getDailyOrdersForLastWeek() {
        LocalDateTime oneWeekAgo = LocalDateTime.now().minusDays(7);
        List<Object[]> results = orderRepository.countOrdersPerDaySince(oneWeekAgo);

        return results.stream()
                .map(row -> new ChartDataDto<>(row[0].toString(), (Long) row[1]))
                .collect(Collectors.toList());
    }

    // ==========================================
    // 3. LEADERBOARDS & RANKINGS
    // ==========================================

    public List<ChartDataDto<String, Long>> getTopMarkets(int limit) {
        // We use PageRequest to efficiently apply a SQL LIMIT statement
        List<Object[]> results = orderRepository.findTopMarketsByOrderCount(PageRequest.of(0, limit));

        return results.stream()
                .map(row -> new ChartDataDto<>(row[0].toString(), (Long) row[1]))
                .collect(Collectors.toList());
    }

    public List<TopCategoryDto> getTopCategoriesByProductCount(int limit) {
        List<Object[]> results = categoryRepository.findTopCategoriesByProductCount(PageRequest.of(0, limit));

        return results.stream()
                .map(row -> new TopCategoryDto(
                        (String) row[0],                                // Category Name
                        row[1] != null ? (String) row[1] : "No Market", // Market Name (with null-safety fallback)
                        (Long) row[2]                                   // Product Count
                ))
                .toList();
    }
}
