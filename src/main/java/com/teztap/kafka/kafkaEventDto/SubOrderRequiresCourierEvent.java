package com.teztap.kafka.kafkaEventDto;

import com.teztap.kafka.PublishableEvent;

public record SubOrderRequiresCourierEvent(Long subOrderId, Long deliveryId) implements PublishableEvent {

    @Override
    public String getTopic() {
        return "suborder-requires-courier";
    }

    @Override
    public String getMessageKey() {
        return String.valueOf(deliveryId);
    }
}
