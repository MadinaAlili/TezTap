package com.teztap.dto;

public record DeliveryOfferResponse(
        Long deliveryId,
        String courier,
        boolean accepted
//        RouteInfo route
){}
