package com.teztap.dto;

import com.teztap.model.Order;

public record DeliveryStatusResponse (
        Long deliveryId,
        Double courierLng,
        Double courierLat,
        Order.OrderStatus status){
}
