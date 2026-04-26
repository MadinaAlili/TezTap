package com.teztap.kafka.kafkaEventDto;

import com.teztap.kafka.PublishableEvent;

import java.math.BigDecimal;

public record OrderRefundRequestedEvent(Long orderId, String reason, BigDecimal refundAmount) implements PublishableEvent {
    @Override
    public String getTopic() {
        return "order-refund-requested";
    }

    @Override
    public String getMessageKey() {
        return String.valueOf(orderId);
    }
}
