package com.teztap.kafka.consumer;

import com.teztap.kafka.kafkaEventDto.OrderPaymentCompletedEvent;
import com.teztap.service.CourierService;
import com.teztap.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderPaymentCompletedConsumer {

    private final CourierService courierService;
    private final OrderService orderService;

    @KafkaListener(topics = "order-payment-completed", groupId = "order-payment-completed-group")
    public void consume(OrderPaymentCompletedEvent event) {
        System.out.println("Received order payment completed event: " + event);
//        orderService.updateStatus(event);
//        courierService.assignCourier(event.orderId());
    }
}
