package com.teztap.kafka.consumer;

import com.teztap.kafka.kafkaEventDto.OrderCreatedEvent;
import com.teztap.service.CartService;
import com.teztap.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderCreatedConsumer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final CartService cartService;
    private final PaymentService paymentService;

    @KafkaListener(topics = "order-created", groupId = "81275")
    public void consume(OrderCreatedEvent event) {
        // initiate order function in orderService directly creates payment url and response that back, this event is ignored for now
    }

}
