package com.teztap.kafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic deliveryFinishedTopic() {
        return new NewTopic("delivery-finished", 3, (short) 1);
    }

    @Bean
    public NewTopic deliveryStartedTopic() {
        return new NewTopic("delivery-started", 3, (short) 1);
    }

    @Bean
    public NewTopic orderCourierAssignedTopic() {
        return new NewTopic("order-courier-assigned", 3, (short) 1);
    }

    @Bean
    public NewTopic orderCreatedTopic() {
        return new NewTopic("order-created", 3, (short) 1);
    }

    @Bean
    public NewTopic orderPaymentCompletedTopic() {
        return new NewTopic("order-payment-completed", 3, (short) 1);
    }

    @Bean
    public NewTopic orderPaymentFailedTopic() {
        return new NewTopic("order-payment-failed", 3, (short) 1);
    }

    @Bean
    public NewTopic productCreatedTopic() { return new NewTopic("product-created", 3, (short) 1); }

    @Bean
    public NewTopic courierNotFoundTopic() { return new NewTopic("courier-not-found", 3, (short) 1); }

    @Bean
    public NewTopic orderDeliveredTopic() { return new NewTopic("order-delivered", 3, (short) 1); }

    @Bean
    public NewTopic subOrderRequiresCourierTopic() { return new NewTopic("suborder-requires-courier", 3, (short) 1); }

    @Bean
    public NewTopic orderCourierUnassignedTopic() { return new NewTopic("order-courier-unassigned", 3, (short) 1); }
}
