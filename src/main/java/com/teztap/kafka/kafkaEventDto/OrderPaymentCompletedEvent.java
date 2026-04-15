package com.teztap.kafka.kafkaEventDto;

import com.teztap.kafka.PublishableEvent;

public record OrderPaymentCompletedEvent(Long orderId)  implements PublishableEvent {
    @Override
    public String getTopic() {
        return "order-payment-completed";
    }

    @Override
    public String getMessageKey() {
        return String.valueOf(orderId);
    }
}
