package com.teztap.kafka.kafkaEventDto;

import com.teztap.kafka.PublishableEvent;

public record CourierNotFoundEvent(Long deliveryId) implements PublishableEvent {
    @Override
    public String getTopic() {
        return "courier-not-found";
    }

    @Override
    public String getMessageKey() {
        return String.valueOf(deliveryId);
    }
}
