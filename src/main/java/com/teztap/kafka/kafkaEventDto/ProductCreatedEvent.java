package com.teztap.kafka.kafkaEventDto;

import com.teztap.dto.ProductDto;
import com.teztap.kafka.PublishableEvent;

import java.util.List;

public record ProductCreatedEvent (List<ProductDto> products) implements PublishableEvent {
    @Override
    public String getTopic() {
        return "product-created";
    }
}
