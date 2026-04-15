package com.teztap.repository;

import com.teztap.model.Order;
import com.teztap.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserOrderByCreatedDesc(User user);

    Optional<Order> findByPaymentId(Long paymentId);

    int countByStatusIn(Collection<Order.OrderStatus> statuses);

}
