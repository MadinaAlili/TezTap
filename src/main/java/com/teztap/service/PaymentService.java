package com.teztap.service;

import com.teztap.kafka.EventPublisher;
import com.teztap.kafka.kafkaEventDto.OrderPaymentCompletedEvent;
import com.teztap.kafka.kafkaEventDto.OrderRefundRequestedEvent;
import com.teztap.model.Order;
import com.teztap.model.Payment;
import com.teztap.repository.OrderRepository;
import com.teztap.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final EventPublisher eventPublisher;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    public static String createFakePaymentLink(Long orderId, String paymentUrl) {
        // In a real app, this would be https://yourdomain.com/...
        return (paymentUrl +"/api/fake-pay/" + orderId);
    }

    public void completePayment(Long orderId){
        // In a real system, you would verify card details here.
        // For this fake one, we just assume the user clicked "Pay" successfully.

        eventPublisher.publish(new OrderPaymentCompletedEvent(orderId));
    }

    @KafkaListener(topics = "order-refund-requested", groupId = "10299")
    public void refundPayment(OrderRefundRequestedEvent event){
        Order order = orderRepository.findById(event.orderId()).get();
        Payment payment = order.getPayment();
        payment.setStatus(Payment.PaymentStatus.REFUNDED);
        orderRepository.save(order);
        paymentRepository.save(payment);
        System.out.println("Refund requested for order " + order.getId());
    }
}
