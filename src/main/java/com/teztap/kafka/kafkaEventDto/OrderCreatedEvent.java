package com.teztap.kafka.kafkaEventDto;

import com.teztap.kafka.PublishableEvent;

public record OrderCreatedEvent(Long orderId, Long userId) implements PublishableEvent {
    @Override
    public String getTopic() {
        return "order-created";
    }

    @Override
    public String getMessageKey() {
        return String.valueOf(orderId);
    }
}