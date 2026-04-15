package com.teztap.kafka.kafkaEventDto;

import com.teztap.kafka.PublishableEvent;

public record DeliveryStartedEvent(Long orderId, Long deliveryId) implements PublishableEvent {
    @Override
    public String getTopic() {
        return "delivery-started";
    }

    @Override
    public String getMessageKey() {
        return String.valueOf(deliveryId);
    }
}
