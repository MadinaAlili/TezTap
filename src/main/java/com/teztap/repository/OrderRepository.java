package com.teztap.repository;

import com.teztap.model.Order;
import com.teztap.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserOrderByCreatedDesc(User user);

    Optional<Order> findByPaymentId(Long paymentId);

    int countByStatusIn(Collection<Order.OrderStatus> statuses);

    // 1. Total Revenue (Traversing into the nested Payment object)
    @Query("SELECT SUM(o.payment.amount) FROM Order o WHERE o.status = 'DELIVERED'")
    BigDecimal calculateTotalRevenue();

    // 2. Count by Status for Pie Chart (Unchanged)
    @Query("SELECT o.status, COUNT(o.id) FROM Order o GROUP BY o.status")
    List<Object[]> countOrdersByStatus();

    // 3. Monthly Revenue for Line Chart (Traversing into the nested Payment object)
    @Query("SELECT EXTRACT(MONTH FROM o.created), SUM(o.payment.amount) FROM Order o " +
            "WHERE EXTRACT(YEAR FROM o.created) = :year AND o.status = 'DELIVERED' " +
            "GROUP BY EXTRACT(MONTH FROM o.created) ORDER BY EXTRACT(MONTH FROM o.created)")
    List<Object[]> sumRevenueByMonthForYear(@Param("year") int year);

    // 4. Daily Orders for Bar Chart (Unchanged)
    @Query("SELECT FUNCTION('DATE', o.created), COUNT(o.id) FROM Order o " +
            "WHERE o.created >= :sinceDate GROUP BY FUNCTION('DATE', o.created) ORDER BY FUNCTION('DATE', o.created)")
    List<Object[]> countOrdersPerDaySince(@Param("sinceDate") LocalDateTime sinceDate);

    // 5. Top Markets Leaderboard (Unchanged)
    @Query("SELECT m.name, COUNT(DISTINCT o.id) " +
            "FROM Order o JOIN o.subOrders so JOIN so.marketBranch m " +
            "GROUP BY m.id, m.name " +
            "ORDER BY COUNT(DISTINCT o.id) DESC")
    List<Object[]> findTopMarketsByOrderCount(Pageable pageable); // Or whatever your return type is

    long countByStatus(Order.OrderStatus status);

    @Query("SELECT SUM(o.payment.amount) FROM Order o WHERE o.status IN (:statuses)")
    BigDecimal calculateTotalRevenueForStatuses(@Param("statuses") List<Order.OrderStatus> statuses);
}
