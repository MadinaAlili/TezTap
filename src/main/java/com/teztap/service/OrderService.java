package com.teztap.service;

import com.teztap.dto.OrderItemResponse;
import com.teztap.dto.OrderResponse;
import com.teztap.dto.UpdateOrderStatusRequest;
import com.teztap.model.*;
import com.teztap.repository.CartItemRepository;
import com.teztap.repository.OrderRepository;
import com.teztap.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final CartService cartService;

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @Transactional
    public OrderResponse checkout(String username) {
        User user = getUser(username);
        List<CartItem> cartItems = cartItemRepository.findByUser(user);

        if (cartItems.isEmpty()) throw new RuntimeException("Cart is empty");

        Order order = new Order();
        order.setUser(user);

        List<OrderItem> orderItems = cartItems.stream().map(cartItem -> {
            Product p = cartItem.getProduct();
            BigDecimal price = p.getDiscountPrice() != null ? p.getDiscountPrice() : p.getOriginalPrice();

            OrderItem oi = new OrderItem();
            oi.setOrder(order);
            oi.setProduct(p);
            oi.setQuantity(cartItem.getQuantity());
            oi.setPriceAtPurchase(price);
            return oi;
        }).toList();

        BigDecimal total = orderItems.stream()
                .map(oi -> oi.getPriceAtPurchase().multiply(BigDecimal.valueOf(oi.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        order.setItems(orderItems);
        order.setTotalPrice(total);
        orderRepository.save(order);

        cartService.clearCart(user);

        return toResponse(order);
    }

    public List<OrderResponse> getMyOrders(String username) {
        User user = getUser(username);
        return orderRepository.findByUserOrderByCreatedDesc(user)
                .stream().map(this::toResponse).toList();
    }

    public OrderResponse getOrderById(String username, Long orderId, boolean isAdmin) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        if (!isAdmin && !order.getUser().getUsername().equals(username)) {
            throw new RuntimeException("Access denied");
        }

        return toResponse(order);
    }

    // Admin only
    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAll().stream().map(this::toResponse).toList();
    }

    // Admin only
    public OrderResponse updateStatus(Long orderId, UpdateOrderStatusRequest req) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        order.setStatus(req.status());    // ← no .valueOf() needed
        return toResponse(orderRepository.save(order));
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream().map(oi -> new OrderItemResponse(
                oi.getProduct().getId(),
                oi.getProduct().getName(),
                oi.getProduct().getImageUrl(),
                oi.getQuantity(),
                oi.getPriceAtPurchase(),
                oi.getPriceAtPurchase().multiply(BigDecimal.valueOf(oi.getQuantity()))
        )).toList();

        return new OrderResponse(
                order.getId(), order.getStatus(),
                order.getTotalPrice(), order.getCreated(), items
        );
    }
}
