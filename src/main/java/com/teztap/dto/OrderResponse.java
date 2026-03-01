package com.teztap.dto;

import com.teztap.model.OrderStatus;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

public record OrderResponse(
    Long orderId,
    OrderStatus status,
    BigDecimal totalPrice,
    Date created,
    List<OrderItemResponse> items
) {}