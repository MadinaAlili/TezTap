package com.teztap.dto;

import com.teztap.model.Order;

public record UpdateOrderStatusRequest(Order.OrderStatus status) {}
