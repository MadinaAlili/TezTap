package com.teztap.controller;

import com.teztap.service.PaymentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FakePaymentController {

    private final PaymentService paymentService;

    @PostMapping("/fake-pay/{orderId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<String> simulatePayment(@PathVariable Long orderId) {
        paymentService.completePayment(orderId);
        return ResponseEntity.ok("Payment faked successfully for order: " + orderId);
    }

    @Data
    public static class FakePaymentRequest {
        private Long paymentId;
        private BigDecimal payedAmount;
    }
}
