package com.teztap.controller.websocket;

import com.teztap.dto.DeliveryOfferResponse;
import com.teztap.service.MatchingService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class CourierSocket {

    private final MatchingService matchingService;

    // Courier sends accept/reject
    @MessageMapping(WebSocketRoutes.INBOUND_DELIVERY_OFFER_RESPONSE)
    public void respond(DeliveryOfferResponse response, Principal principal) {
        String courierUsername = principal.getName();

        if (response.accepted())
            matchingService.acceptOrder(response.deliveryId(), courierUsername);
        else
            matchingService.rejectOrder(response.deliveryId(), courierUsername);
    }
}
