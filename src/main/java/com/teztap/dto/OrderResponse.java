package com.teztap.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class OrderResponse {
    // Human-readable ID for the user to see
    private Long orderId;

    // The final price calculated by your service (Subtotal + Shipping - Discounts)
    private BigDecimal totalAmount;

    // The URL where the user will perform the transaction (Stripe/Payriff/etc.)
    private String paymentUrl;

    // Current status (e.g., PENDING_PAYMENT)
    private String status;

    // Critical for UX: Tell the frontend when this link becomes useless
    private LocalDateTime expiresAt;

    // Optional: A message to show the user (e.g., "Redirecting to payment...")
    private String message;
}