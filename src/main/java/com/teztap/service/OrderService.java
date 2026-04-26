package com.teztap.service;

import com.teztap.dto.*;
import com.teztap.kafka.EventPublisher;
import com.teztap.kafka.kafkaEventDto.OrderCourierAssignedEvent;
import com.teztap.kafka.kafkaEventDto.OrderCreatedEvent;
import com.teztap.kafka.kafkaEventDto.OrderPaymentCompletedEvent;
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
//    private final AddressRepository addressRepository;
    private final CartService cartService;

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
        System.err.println("ORDER PAYMENT COMPLETED IN DELIVERY SERVICE");
        Order order = orderRepository.findById(event.orderId()).get();
        order.setStatus(Order.OrderStatus.WAITING_FOR_COURIER);
        order.getPayment().setStatus(Payment.PaymentStatus.PAID);
        orderRepository.save(order);
        paymentRepository.save(order.getPayment());

        // Delete all cart items for the user after successful payment
        List<CartItem> userCartItems = cartItemRepository.findAllByUserId(order.getUser().getId());
        cartItemRepository.deleteAll(userCartItems);
        
        // Trigger notification, courier matching, etc.
    }

    @Transactional
    public OrderResponse initiateOrder(String username, OrderRequest orderRequest) {
        User user = getUser(username);

        // 1. Fetch Cart Items
        List<CartItem> cartItems = cartItemRepository.findAllByUserId(user.getId());
        if (cartItems.isEmpty()) throw new RuntimeException("Cart is empty");

        // 2. Create the Parent Order
        Order order = new Order();
        order.setUser(user);
        order.setDeliveryNote(orderRequest.getNote());
        order.setOrderAddress(mapToAddress(orderRequest.getDeliveryAddress()));

        // 3. Generate the SubOrders using our new method!
        // (Assuming orderRequest.getBranchIds() is a List<Long> of all branches involved)
        List<SubOrder> subOrders = groupItemsIntoSubOrders(order, cartItems, orderRequest.getBranchIds());

        // Link the SubOrders to the Parent Order
        order.setSubOrders(subOrders);

        // 4. Calculate total price safely across all sub-orders
        BigDecimal totalPrice = subOrders.stream()
                .flatMap(subOrder -> subOrder.getItems().stream()) // Flatten all items
                .map(item -> item.getPriceAtPurchase().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Payment payment = new Payment()
                .setPaymentMethod(orderRequest.getPaymentMethod())
                .setStatus(Payment.PaymentStatus.PENDING)
                .setAmount(totalPrice);

        paymentRepository.save(payment);
        order.setPayment(payment);

        // 5. Save the Parent Order (CascadeType.ALL will save SubOrders and OrderItems automatically)
        Order savedOrder = orderRepository.save(order);

        // 6. Clear the cart securely
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

    @KafkaListener(topics = "order-courier-assigned")
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
                .setPaymentUrl(PaymentService.createFakePaymentLink(order.getId(), paymentUrl))
                .setStatus(order.getStatus().toString());
    }

    public boolean isOrderOwnedByUser(Long orderId, String username) {
        Order order = orderRepository.findById(orderId).get();
        return order.getUser().getUsername().equals(username);
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

    @Transactional(readOnly = true)
    @SneakyThrows
    public List<SubOrderDto> getOrderItemsGroupedByBranch(String username, Long orderId, boolean isAdmin) {

        // 1. Fetch the order
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found with id: " + orderId));

        // 2. Security Check
        if (!isAdmin && !order.getUser().getUsername().equals(username)) {
            throw new AccessDeniedException("You are not authorized to view the items of this order.");
        }

        // 3. Map the SubOrders and their nested Items
        return order.getSubOrders().stream()
                .map(subOrder -> {
                    // Extract the Address entity from the branch
                    Address branchAddressEntity = subOrder.getMarketBranch().getAddress();

                    // Map it to AddressDto (Point X is Longitude, Y is Latitude)
                    AddressDto branchAddressDto = new AddressDto(
                            branchAddressEntity.getLocation().getX(), // Longitude
                            branchAddressEntity.getLocation().getY(), // Latitude
                            branchAddressEntity.getFullAddress(),
                            branchAddressEntity.getAdditionalInfo()
                    );

                    return new SubOrderDto(
                            subOrder.getId(),
                            subOrder.getMarketBranch().getId(),
                            subOrder.getMarketBranch().getMarket().getName(),
                            branchAddressDto, // Pass the mapped DTO here
                            subOrder.getStatus().name(),

                            // Map the items
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

        // 1. Fetch the Order
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found with id: " + orderId));

        // 2. Security Check
        if (!isAdmin && !order.getUser().getUsername().equals(username)) {
            throw new AccessDeniedException("You are not authorized to view this order.");
        }

        // 3. Map the Embedded Address to AddressDto
        Address addr = order.getOrderAddress();
        AddressDto addressDto = null;
        if (addr != null && addr.getLocation() != null) {
            addressDto = new AddressDto(
                    addr.getLocation().getX(), // Longitude
                    addr.getLocation().getY(), // Latitude
                    addr.getFullAddress(),
                    addr.getAdditionalInfo()
            );
        }

        // 4. Map the SubOrders to a lightweight summary list
        List<SubOrderSummaryDto> deliverySummaries = order.getSubOrders().stream()
                .map(subOrder -> new SubOrderSummaryDto(
                        subOrder.getId(),
                        subOrder.getMarketBranch().getMarket().getName(),
                        subOrder.getStatus().name()
                )).toList();

        // 5. Return the updated OrderSummaryDto
        return new OrderSummaryDto(
                order.getId(),
                order.getStatus().name(),
                order.getCreated(),
                order.getUpdated(),
                order.getDeliveryNote(),
                addressDto,
                order.getPayment() != null ? order.getPayment().getId() : null,
                deliverySummaries // The new list of branches
        );
    }

    private List<SubOrder> groupItemsIntoSubOrders(Order parentOrder, List<CartItem> cartItems, List<Long> requestedBranchIds) {

        // 1. Fetch the actual branch entities requested by the user
        List<MarketBranch> selectedBranches = marketBranchRepository.findAllById(requestedBranchIds);

        // 2. Create a fast lookup map: { MarketId -> MarketBranch }
        // This tells us: "For Market X, the user chose Branch Y"
        Map<Long, MarketBranch> marketToBranchMap = selectedBranches.stream()
                .collect(Collectors.toMap(
                        branch -> branch.getMarket().getId(),
                        branch -> branch,
                        // THE FIX: If there are duplicate branches for the same market, keep the first one
                        (existingBranch, duplicateBranch) -> existingBranch
                ));

        // 3. Prepare our map to group CartItems by Branch
        Map<MarketBranch, List<CartItem>> itemsByBranch = new HashMap<>();

        // 4. Sort every CartItem into the correct Branch bucket
        for (CartItem cartItem : cartItems) {
            Product product = cartItem.getProduct();
            Long productMarketId = product.getMarket().getId();

            MarketBranch assignedBranch = marketToBranchMap.get(productMarketId);

            if (assignedBranch == null) {
                // Security/Validation check: The user has an item in their cart,
                // but the frontend didn't send a branch ID for that item's market!
                throw new IllegalArgumentException(
                        "No branch selected for market: " + product.getMarket().getName()
                                + " (Product: " + product.getName() + ")"
                );
            }

            itemsByBranch.computeIfAbsent(assignedBranch, k -> new ArrayList<>()).add(cartItem);
        }

        // 5. Convert these buckets into SubOrder entities
        List<SubOrder> subOrders = new ArrayList<>();

        for (Map.Entry<MarketBranch, List<CartItem>> entry : itemsByBranch.entrySet()) {
            MarketBranch branch = entry.getKey();
            List<CartItem> branchCartItems = entry.getValue();

            SubOrder subOrder = new SubOrder();
            subOrder.setParentOrder(parentOrder);
            subOrder.setMarketBranch(branch);
            subOrder.setStatus(Order.OrderStatus.PENDING); // Initial status

            // Map CartItems to OrderItems for this specific SubOrder
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
