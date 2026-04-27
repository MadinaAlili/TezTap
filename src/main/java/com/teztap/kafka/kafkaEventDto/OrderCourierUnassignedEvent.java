package com.teztap.kafka.kafkaEventDto;

import com.teztap.kafka.PublishableEvent;

public record OrderCourierUnassignedEvent(String courierUsername) implements PublishableEvent {
    @Override
    public String getTopic() {
        return "order-courier-unassigned";
    }

    @Override
    public String getMessageKey() {
        return courierUsername;
    }
}
