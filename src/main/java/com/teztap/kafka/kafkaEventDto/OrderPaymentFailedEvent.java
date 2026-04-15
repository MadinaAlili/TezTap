package com.teztap.kafka.kafkaEventDto;

import com.teztap.kafka.PublishableEvent;

public record OrderPaymentFailedEvent(Long orderId)  implements PublishableEvent {
    @Override
    public String getTopic() {
        return "order-payment-failed";
    }

    @Override
    public String getMessageKey() {
        return String.valueOf(orderId);
    }
}