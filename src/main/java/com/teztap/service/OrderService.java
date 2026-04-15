package com.teztap.service;

import com.teztap.dto.*;
import com.teztap.kafka.EventPublisher;
import com.teztap.kafka.kafkaEventDto.OrderCourierAssignedEvent;
import com.teztap.kafka.kafkaEventDto.OrderCreatedEvent;
import com.teztap.kafka.kafkaEventDto.OrderPaymentCompletedEvent;
import com.teztap.model.*;
import com.teztap.repository.CartItemRepository;
import com.teztap.repository.OrderRepository;
import com.teztap.repository.ProductRepository;
import com.teztap.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
//    private final AddressRepository addressRepository;
    private final CartService cartService;

    private final EventPublisher eventPublisher;

    @Bean
    public SurgeService.ActiveOrderCounter activeOrderCounter(OrderRepository repo) {
        return () -> repo.countByStatusIn(List.of(Order.OrderStatus.WAITING_FOR_COURIER, Order.OrderStatus.WAITING_FOR_SHIPMENT, Order.OrderStatus.PENDING));
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @Transactional
    @KafkaListener(topics = "order-payment-completed", groupId = "order-payment-completed-group")
    public void finalizeOrder(OrderPaymentCompletedEvent event) {
        Order order = orderRepository.findById(event.orderId()).get();
        order.setStatus(Order.OrderStatus.WAITING_FOR_COURIER);
        order.getPayment().setStatus(Payment.PaymentStatus.PAID);
        // Trigger notification, courier matching, etc.
    }

    @Transactional
    public OrderResponse initiateOrder(String username, OrderRequest orderRequest) {
        User user = getUser(username); // Fetch user

        // Fetch everything in ONE query (Prevents N+1)
        List<CartItem> cartItems = cartItemRepository.findAllByUserId(user.getId());
        if (cartItems.isEmpty()) throw new RuntimeException("Cart is empty");


        // Map DTO to Entity
        Order order = new Order();
        order.setStatus(Order.OrderStatus.PENDING);
        order.setPayment(new Payment().setPaymentMethod(orderRequest.getPaymentMethod())
                .setStatus(Payment.PaymentStatus.PENDING)
                .setAmount(calculateTotalPrice(orderRequest)));
        order.setDeliveryNote(orderRequest.getNote());
        // Convert AddressDTO to Embedded Address
        order.setOrderAddress(mapToAddress(orderRequest.getDeliveryAddress()));
        order.setUser(getUser(username));
        order.setMarketBranch(new MarketBranch().setId(orderRequest.getBranchId()));
        // set order items
        order.setItems(mapOrderRequestToOrderItems(orderRequest, order));

        // Save order
        Order savedOrder = orderRepository.save(order);

        // Send order created event to Kafka
        eventPublisher.publish(new OrderCreatedEvent(savedOrder.getId(), user.getId()));
        return toOrderResponse(order);
    }

    public List<OrderResponse> getMyOrders(String username) {
        User user = getUser(username);
        return orderRepository.findByUserOrderByCreatedDesc(user)
                .stream().map(this::toOrderResponse).toList();
    }

    public OrderResponse getOrderById(String username, Long orderId, boolean isAdmin) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        if (!isAdmin && !order.getUser().getUsername().equals(username)) {
            throw new RuntimeException("Access denied");
        }

        return toOrderResponse(order);
    }

    // Admin only
    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAll().stream().map(this::toOrderResponse).toList();
    }

    // Admin only
    public OrderResponse updateStatus(Long orderId, UpdateOrderStatusRequest req) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        order.setStatus(req.status());    // ← no .valueOf() needed
        return toOrderResponse(orderRepository.save(order));
    }

    @KafkaListener(topics = "order-courier-assigned", groupId = "order-courier-assigned-group")
    public Order updateStatus(OrderCourierAssignedEvent event) {
        Order order = orderRepository.findById(event.deliveryId()).get();
        order.setStatus(Order.OrderStatus.ON_THE_WAY);
        return orderRepository.save(order);
    }


    public boolean isWaitingForCourier(Long orderId) {
        Order order = orderRepository.findById(orderId).get();
        return order.getStatus() == Order.OrderStatus.WAITING_FOR_COURIER;
    }

    private OrderResponse toOrderResponse(Order order) {
        return new OrderResponse().setExpiresAt(LocalDateTime.now().plusHours(1))
                .setMessage("Order created successfully")
                .setOrderId(order.getId())
                .setPaymentUrl(PaymentService.createFakePaymentLink(order.getId()))
                .setStatus(order.getStatus().toString());
    }

    public boolean isOrderOwnedByUser(Long orderId, String username) {
        Order order = orderRepository.findById(orderId).get();
        return order.getUser().getUsername().equals(username);
    }

    public BigDecimal calculateTotalPrice(OrderRequest orderRequest) {
        List<OrderItem> items = mapOrderRequestToOrderItems(orderRequest, null);
        return items.stream()
                .map(item -> item.getPriceAtPurchase().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<OrderItem> mapOrderRequestToOrderItems(OrderRequest request, Order order) {
        List<Long> productIds = request.getItems().stream().map(CartItemRequest::getProductId).toList();
        List<Product> products = productRepository.findAllById(productIds);
        return products.stream().map(p -> new OrderItem()
                .setOrder(order)
                .setProduct(p)
                .setQuantity(request.getItems().stream().filter(i -> i.getProductId().equals(p.getId())).findFirst().get().getQuantity())
                .setPriceAtPurchase(p.getDiscountPrice() != null ? p.getDiscountPrice() : p.getOriginalPrice())).toList();
    }

    private Address mapToAddress(AddressDto dto) {
        Address address = new Address();
//        address.setCity(dto.getCity());
//        address.setDistrict(dto.getDistrict());
        address.setFullAddress(dto.fullAddress());
        address.setAdditionalInfo(dto.additionalInfo());

        // Use Geometry utility
        address.setLocation(GeometryUtils.createPoint(
                BigDecimal.valueOf(dto.longitude()),
                BigDecimal.valueOf(dto.latitude())
        ));
        return address;
    }
}
