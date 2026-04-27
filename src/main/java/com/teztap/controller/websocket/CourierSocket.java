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
        // ── DEBUG ──────────────────────────────────────────────────────────────
        System.err.println("[CourierSocket] >>> respond() ENTERED");
        System.err.println("[CourierSocket]     principal = " + (principal != null ? principal.getName() : "NULL ← JWT not set on session!"));
        System.err.println("[CourierSocket]     response  = " + response);
        System.err.println("[CourierSocket]     accepted  = " + (response != null ? response.accepted() : "NULL RESPONSE BODY"));
        System.err.println("[CourierSocket]     deliveryId= " + (response != null ? response.deliveryId() : "n/a"));
        // ──────────────────────────────────────────────────────────────────────

        if (principal == null) {
            System.err.println("[CourierSocket] ERROR: principal is null — message will be dropped. Check JWT interceptor in WebSocketConfig.");
            return;
        }

        String courierUsername = principal.getName();

        if (response == null) {
            System.err.println("[CourierSocket] ERROR: response body is null — check that the client is sending valid JSON matching DeliveryOfferResponse");
            return;
        }

        if (response.accepted()) {
            System.err.println("[CourierSocket] >>> calling matchingService.acceptOrder(deliveryId=" + response.deliveryId() + ", courier=" + courierUsername + ")");
            try {
                matchingService.acceptOrder(response.deliveryId(), courierUsername);
                System.err.println("[CourierSocket] <<< acceptOrder() completed OK");
            } catch (Exception e) {
                System.err.println("[CourierSocket] ERROR in acceptOrder(): " + e.getClass().getSimpleName() + ": " + e.getMessage());
                e.printStackTrace(System.err);
            }
        } else {
            System.err.println("[CourierSocket] >>> calling matchingService.rejectOrder(deliveryId=" + response.deliveryId() + ", courier=" + courierUsername + ")");
            try {
                matchingService.rejectOrder(response.deliveryId(), courierUsername);
                System.err.println("[CourierSocket] <<< rejectOrder() completed OK");
            } catch (Exception e) {
                System.err.println("[CourierSocket] ERROR in rejectOrder(): " + e.getClass().getSimpleName() + ": " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }

        System.err.println("[CourierSocket] <<< respond() DONE");
    }

    @MessageMapping(WebSocketRoutes.INBOUND_DELIVERY_FINISHED)
    public void respondFinished(DeliveryFinishedResponse response, Principal principal) {
        // ── DEBUG ──────────────────────────────────────────────────────────────
        System.err.println("[CourierSocket] >>> respondFinished() ENTERED");
        System.err.println("[CourierSocket]     principal = " + (principal != null ? principal.getName() : "NULL ← JWT not set on session!"));
        System.err.println("[CourierSocket]     response  = " + response);
        // ──────────────────────────────────────────────────────────────────────

        if (principal == null) {
            System.err.println("[CourierSocket] ERROR: principal is null — message will be dropped.");
            return;
        }

        String courierUsername = principal.getName();

        try {
            System.err.println("[CourierSocket] >>> calling deliveryService.finishDelivery(courier=" + courierUsername + ")");
            deliveryService.finishDelivery(response, courierUsername);
            System.err.println("[CourierSocket] <<< finishDelivery() completed OK");
        } catch (Exception e) {
            System.err.println("[CourierSocket] ERROR in finishDelivery(): " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }
}
