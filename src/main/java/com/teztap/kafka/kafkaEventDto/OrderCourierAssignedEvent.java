package com.teztap.kafka.kafkaEventDto;

import com.teztap.kafka.PublishableEvent;

public record OrderCourierAssignedEvent(Long deliveryId, String courierUsername)  implements PublishableEvent {
    @Override
    public String getTopic() {
        return "order-courier-assigned";
    }

    @Override
    public String getMessageKey() {
        return String.valueOf(deliveryId);
    }
}
