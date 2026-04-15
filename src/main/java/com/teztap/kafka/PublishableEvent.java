package com.teztap.kafka;

public interface PublishableEvent {
    /**
     * The target Kafka topic for this event.
     */
    String getTopic();

    /**
     * The partition key (e.g., deliveryId or orderId).
     * Return null if you don't care about ordering.
     */
    default String getMessageKey() {
        return null;
    }
}