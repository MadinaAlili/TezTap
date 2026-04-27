package com.teztap.service;

import com.teztap.dto.*;
import com.teztap.kafka.EventPublisher;
import com.teztap.kafka.kafkaEventDto.OrderCourierAssignedEvent;
import com.teztap.kafka.kafkaEventDto.OrderCreatedEvent;
import com.teztap.kafka.kafkaEventDto.OrderPaymentCompletedEvent;
import com.teztap.kafka.kafkaEventDto.OrderRefundRequestedEvent;
import com.teztap.model.*;
import com.teztap.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.AccessDeniedException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;
    private final MarketBranchRepository marketBranchRepository;
    private final CartService cartService;
    private final DeliveryRepository deliveryRepository; // Fix 2: needed to navigate delivery → order

    @Value("${payment.url:http://localhost:5000}")
    private String paymentUrl;

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
    @KafkaListener(topics = "order-payment-completed")
    public void finalizeOrder(OrderPaymentCompletedEvent event) {
        System.err.println("ORDER PAYMENT COMPLETED IN ORDER SERVICE");
        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + event.orderId()));
        order.setStatus(Order.OrderStatus.WAITING_FOR_COURIER);
        order.getPayment().setStatus(Payment.PaymentStatus.PAID);
        orderRepository.save(order);
        paymentRepository.save(order.getPayment());

        List<CartItem> userCartItems = cartItemRepository.findAllByUserId(order.getUser().getId());
        cartItemRepository.deleteAll(userCartItems);
    }

    @Transactional
    public OrderResponse initiateOrder(String username, OrderRequest orderRequest) {
        User user = getUser(username);

        List<CartItem> cartItems = cartItemRepository.findAllByUserId(user.getId());
        if (cartItems.isEmpty()) throw new RuntimeException("Cart is empty");

        Order order = new Order();
        order.setUser(user);
        order.setDeliveryNote(orderRequest.getNote());
        order.setOrderAddress(mapToAddress(orderRequest.getDeliveryAddress()));

        List<SubOrder> subOrders = groupItemsIntoSubOrders(order, cartItems, orderRequest.getBranchIds());
        order.setSubOrders(subOrders);

        BigDecimal totalPrice = subOrders.stream()
                .flatMap(subOrder -> subOrder.getItems().stream())
                .map(item -> item.getPriceAtPurchase().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Payment payment = new Payment()
                .setPaymentMethod(orderRequest.getPaymentMethod())
                .setStatus(Payment.PaymentStatus.PENDING)
                .setAmount(totalPrice);

        paymentRepository.save(payment);
        order.setPayment(payment);

        Order savedOrder = orderRepository.save(order);

        List<Long> orderedCartItemIds = cartItems.stream().map(CartItem::getId).toList();
        cartService.removeSelectedCartItems(username, orderedCartItemIds);

        eventPublisher.publish(new OrderCreatedEvent(savedOrder.getId(), user.getId()));

        return toOrderResponse(savedOrder);
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

    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAll().stream().map(this::toOrderResponse).toList();
    }

    public OrderResponse updateStatus(Long orderId, UpdateOrderStatusRequest req) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        order.setStatus(req.status());
        return toOrderResponse(orderRepository.save(order));
    }

    // Fix 2: the event carries a deliveryId, not an orderId.
    // Navigate delivery → subOrder → parentOrder to update the correct Order row.
    @Transactional
    @KafkaListener(topics = "order-courier-assigned")
    public void updateStatusOnCourierAssigned(OrderCourierAssignedEvent event) {
        Delivery delivery = deliveryRepository.findById(event.deliveryId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Delivery not found for id: " + event.deliveryId() +
                        " — cannot update order status to ON_THE_WAY"));

        Order order = delivery.getSubOrder().getParentOrder();
        order.setStatus(Order.OrderStatus.ON_THE_WAY);
        orderRepository.save(order);

        System.err.println("[OrderService] updateStatusOnCourierAssigned: Order " +
                order.getId() + " → ON_THE_WAY (triggered by delivery " + event.deliveryId() + ")");
    }

    // Listens to order-refund-requested (published by DeliveryService.handleCourierNotFound).
    // Payment service sets payment → REFUNDED on the same topic.
    // This listener sets the order → CANCELLED_COURIER_NOT_FOUND so the DB reflects
    // the full cancellation. DeliveryService already sets it on subOrders and conditionally
    // on the parent, but only when ALL branches fail. This ensures the parent order status
    // is always updated when a refund is issued, regardless of branch count.
    @Transactional
    @KafkaListener(topics = "order-refund-requested", groupId = "order-service-refund")
    public void handleRefundRequested(OrderRefundRequestedEvent event) {
        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + event.orderId()));

        // Only update if not already in a terminal state to stay idempotent
        if (order.getStatus() == Order.OrderStatus.CANCELLED_COURIER_NOT_FOUND
                || order.getStatus() == Order.OrderStatus.DELIVERED
                || order.getStatus() == Order.OrderStatus.CANCELLED) {
            System.err.println("[OrderService] handleRefundRequested: order " + event.orderId()
                    + " already in terminal state " + order.getStatus() + " — skipping");
            return;
        }

        order.setStatus(Order.OrderStatus.CANCELLED_COURIER_NOT_FOUND);
        orderRepository.save(order);

        System.err.println("[OrderService] handleRefundRequested: order " + event.orderId()
                + " → CANCELLED_COURIER_NOT_FOUND (refund amount=" + event.refundAmount() + ")");
    }

    public boolean isWaitingForCourier(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));
        return order.getStatus() == Order.OrderStatus.WAITING_FOR_COURIER;
    }

    private OrderResponse toOrderResponse(Order order) {
        return new OrderResponse().setExpiresAt(LocalDateTime.now().plusHours(1))
                .setMessage("Order created successfully")
                .setOrderId(order.getId())
                .setPaymentUrl(PaymentService.createFakePaymentLink(order.getId(), paymentUrl))
                .setStatus(order.getStatus().toString());
    }

    public boolean isOrderOwnedByUser(Long orderId, String username) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));
        return order.getUser().getUsername().equals(username);
    }

    private Address mapToAddress(AddressDto dto) {
        Address address = new Address();
        address.setFullAddress(dto.fullAddress());
        address.setAdditionalInfo(dto.additionalInfo());
        address.setLocation(GeometryUtils.createPoint(
                BigDecimal.valueOf(dto.longitude()),
                BigDecimal.valueOf(dto.latitude())
        ));
        return address;
    }

    @Transactional(readOnly = true)
    @SneakyThrows
    public List<SubOrderDto> getOrderItemsGroupedByBranch(String username, Long orderId, boolean isAdmin) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found with id: " + orderId));

        if (!isAdmin && !order.getUser().getUsername().equals(username)) {
            throw new AccessDeniedException("You are not authorized to view the items of this order.");
        }

        return order.getSubOrders().stream()
                .map(subOrder -> {
                    Address branchAddressEntity = subOrder.getMarketBranch().getAddress();
                    AddressDto branchAddressDto = new AddressDto(
                            branchAddressEntity.getLocation().getX(),
                            branchAddressEntity.getLocation().getY(),
                            branchAddressEntity.getFullAddress(),
                            branchAddressEntity.getAdditionalInfo()
                    );
                    return new SubOrderDto(
                            subOrder.getId(),
                            subOrder.getMarketBranch().getId(),
                            subOrder.getMarketBranch().getMarket().getName(),
                            branchAddressDto,
                            subOrder.getStatus().name(),
                            subOrder.getItems().stream()
                                    .map(item -> new OrderItemDto(
                                            item.getProduct().getId(),
                                            item.getProduct().getName(),
                                            item.getProduct().getImageUrl(),
                                            item.getQuantity(),
                                            item.getPriceAtPurchase()
                                    )).toList()
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    @SneakyThrows
    public OrderSummaryDto getOrderSummary(String username, Long orderId, boolean isAdmin) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found with id: " + orderId));

        if (!isAdmin && !order.getUser().getUsername().equals(username)) {
            throw new AccessDeniedException("You are not authorized to view this order.");
        }

        Address addr = order.getOrderAddress();
        AddressDto addressDto = null;
        if (addr != null && addr.getLocation() != null) {
            addressDto = new AddressDto(
                    addr.getLocation().getX(),
                    addr.getLocation().getY(),
                    addr.getFullAddress(),
                    addr.getAdditionalInfo()
            );
        }

        List<SubOrderSummaryDto> deliverySummaries = order.getSubOrders().stream()
                .map(subOrder -> new SubOrderSummaryDto(
                        subOrder.getId(),
                        subOrder.getMarketBranch().getMarket().getName(),
                        subOrder.getStatus().name()
                )).toList();

        return new OrderSummaryDto(
                order.getId(),
                order.getStatus().name(),
                order.getCreated(),
                order.getUpdated(),
                order.getDeliveryNote(),
                addressDto,
                order.getPayment() != null ? order.getPayment().getId() : null,
                deliverySummaries
        );
    }

    private List<SubOrder> groupItemsIntoSubOrders(Order parentOrder, List<CartItem> cartItems, List<Long> requestedBranchIds) {
        List<MarketBranch> selectedBranches = marketBranchRepository.findAllById(requestedBranchIds);

        Map<Long, MarketBranch> marketToBranchMap = selectedBranches.stream()
                .collect(Collectors.toMap(
                        branch -> branch.getMarket().getId(),
                        branch -> branch,
                        (existingBranch, duplicateBranch) -> existingBranch
                ));

        Map<MarketBranch, List<CartItem>> itemsByBranch = new HashMap<>();

        for (CartItem cartItem : cartItems) {
            Product product = cartItem.getProduct();
            Long productMarketId = product.getMarket().getId();
            MarketBranch assignedBranch = marketToBranchMap.get(productMarketId);
            if (assignedBranch == null) {
                throw new IllegalArgumentException(
                        "No branch selected for market: " + product.getMarket().getName()
                        + " (Product: " + product.getName() + ")"
                );
            }
            itemsByBranch.computeIfAbsent(assignedBranch, k -> new ArrayList<>()).add(cartItem);
        }

        List<SubOrder> subOrders = new ArrayList<>();
        for (Map.Entry<MarketBranch, List<CartItem>> entry : itemsByBranch.entrySet()) {
            MarketBranch branch = entry.getKey();
            List<CartItem> branchCartItems = entry.getValue();

            SubOrder subOrder = new SubOrder();
            subOrder.setParentOrder(parentOrder);
            subOrder.setMarketBranch(branch);
            subOrder.setStatus(Order.OrderStatus.PENDING);

            List<OrderItem> subOrderItems = branchCartItems.stream().map(cartItem -> new OrderItem()
                    .setSubOrder(subOrder)
                    .setProduct(cartItem.getProduct())
                    .setQuantity(cartItem.getQuantity())
                    .setPriceAtPurchase(cartItem.getProduct().getDiscountPrice() != null
                            ? cartItem.getProduct().getDiscountPrice()
                            : cartItem.getProduct().getOriginalPrice())
            ).toList();

            subOrder.setItems(subOrderItems);
            subOrders.add(subOrder);
        }

        return subOrders;
    }
}
