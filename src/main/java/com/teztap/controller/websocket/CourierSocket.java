package com.teztap.controller.websocket;

import com.teztap.dto.DeliveryFinishedResponse;
import com.teztap.dto.DeliveryOfferResponse;
import com.teztap.service.DeliveryService;
import com.teztap.service.MatchingService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class CourierSocket {

    private final MatchingService matchingService;
    private final DeliveryService deliveryService;

    // Courier sends accept/reject
    @MessageMapping(WebSocketRoutes.INBOUND_DELIVERY_OFFER_RESPONSE)
    public void respond(DeliveryOfferResponse response, Principal principal) {
        String courierUsername = principal.getName();

        if (response.accepted())
            matchingService.acceptOrder(response.deliveryId(), courierUsername);
        else
            matchingService.rejectOrder(response.deliveryId(), courierUsername);
    }

    @MessageMapping(WebSocketRoutes.INBOUND_DELIVERY_FINISHED)
    public void respondFinished(DeliveryFinishedResponse response, Principal principal) {
        String courierUsername = principal.getName();

        deliveryService.finishDelivery(response, courierUsername);
    }
}
