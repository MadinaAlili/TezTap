package com.teztap.controller.websocket;

/**
 * Fix 10: CustomerSocket no longer has a poll-on-demand handler.
 *
 * Previous design: customer sends /app/delivery/status → gets one snapshot back.
 * Problem: only gets a position when the customer explicitly asks, conflicts with
 * the push path in CourierService, and is the wrong pattern for mobile.
 *
 * Correct design (now active):
 *   Courier sends location → /app/location
 *   → CourierService.updateCourierLocation() pushes to the customer automatically
 *   → Customer subscribes to /user/queue/delivery/status and receives updates passively
 *
 * This class is kept as a placeholder for future customer-facing socket handlers.
 */
import org.springframework.stereotype.Controller;

@Controller
public class CustomerSocket {
    // Customer receives location updates passively via
    // CourierService.updateCourierLocation() → convertAndSendToUser().
    //
    // Add future customer WebSocket handlers here with @MessageMapping if needed.
}
