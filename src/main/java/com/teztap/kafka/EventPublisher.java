package com.teztap.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(PublishableEvent event) {
        String topic = event.getTopic();
        String key = event.getMessageKey();

        log.debug("Publishing event to topic [{}]: {}", topic, event);

        if (key != null) {
            kafkaTemplate.send(topic, key, event);
        } else {
            kafkaTemplate.send(topic, event);
        }
    }
}
