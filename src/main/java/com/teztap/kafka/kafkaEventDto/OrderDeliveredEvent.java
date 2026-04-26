package com.teztap.kafka.kafkaEventDto;

import com.teztap.kafka.PublishableEvent;

import java.time.LocalDateTime;

public record OrderDeliveredEvent(Long orderId, Long deliveryId, String courierUsername,
                                  LocalDateTime deliveryTime) implements PublishableEvent {
    @Override
    public String getTopic() {
        return "order-delivered";
    }

    @Override
    public String getMessageKey() {
        return PublishableEvent.super.getMessageKey();
    }
}
