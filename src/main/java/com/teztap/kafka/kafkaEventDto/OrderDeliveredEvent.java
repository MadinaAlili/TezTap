package com.teztap.kafka.kafkaEventDto;

import com.teztap.kafka.PublishableEvent;

// LocalDateTime removed — Jackson cannot serialize java.time types without the JSR310 module,
// which was causing SerializationException and rolling back the finishDelivery transaction.
// Consumers that need the delivery time should read it from the deliveries table using deliveryId.
public record OrderDeliveredEvent(Long orderId, Long deliveryId,
                                  String courierUsername) implements PublishableEvent {
    @Override
    public String getTopic() {
        return "order-delivered";
    }

    @Override
    public String getMessageKey() {
        return PublishableEvent.super.getMessageKey();
    }
}
