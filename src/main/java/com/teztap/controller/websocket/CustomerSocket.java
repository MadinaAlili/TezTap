package com.teztap.controller.websocket;

import com.teztap.dto.DeliveryLocationRequest;
import com.teztap.dto.DeliveryStatusResponse;
import com.teztap.model.Order;
import com.teztap.security.CustomUserDetails;
import com.teztap.service.CourierService;
import com.teztap.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Point;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class CustomerSocket {

    private final OrderService orderService;
    private final CourierService courierService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping(WebSocketRoutes.INBOUND_DELIVERY_STATUS_UPDATE)
    public void getDeliveryLocation(DeliveryLocationRequest request,
                                    Principal principal) {

        String customerUsername = principal.getName();

        // check ownership
        if (!orderService.isOrderOwnedByUser(request.orderId(), customerUsername)) {
            return; // silently ignore (or throw)
        }

        // get courier location (from Redis ideally)
        Point point = courierService.getCourierLocation(request.orderId());

        if (point == null) return;

        DeliveryStatusResponse response = new DeliveryStatusResponse(
                request.orderId(),
                point.getX(), // lng
                point.getY(),  // lat
                Order.OrderStatus.ON_THE_WAY
        );

        // send back ONLY to that user
        messagingTemplate.convertAndSendToUser(
                customerUsername,
                WebSocketRoutes.CUSTOMER_DELIVERY_STATUS,
                response
        );
    }
}
