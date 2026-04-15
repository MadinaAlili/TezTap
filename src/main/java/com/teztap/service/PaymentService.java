package com.teztap.service;

import com.teztap.kafka.EventPublisher;
import com.teztap.kafka.kafkaEventDto.OrderPaymentCompletedEvent;
import com.teztap.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final EventPublisher eventPublisher;
    private final OrderRepository orderRepository;

    public boolean charge(Long orderId) {
        return true; // fake the payment
    }

    public static String createFakePaymentLink(Long orderId) {
        // In a real app, this would be https://yourdomain.com/...
        return "http://localhost:5000/api/fake-pay/" + orderId;
    }

    public void completePayment(Long orderId){
        // In a real system, you would verify card details here.
        // For this fake one, we just assume the user clicked "Pay" successfully.

        eventPublisher.publish(new OrderPaymentCompletedEvent(orderId));
    }
}
