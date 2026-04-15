package com.teztap.kafka.kafkaEventDto;

import com.teztap.dto.ProductDTO;
import com.teztap.kafka.PublishableEvent;

import java.util.List;

public record ProductCreatedEvent (List<ProductDTO> products) implements PublishableEvent {
    @Override
    public String getTopic() {
        return "product-created";
    }
}
