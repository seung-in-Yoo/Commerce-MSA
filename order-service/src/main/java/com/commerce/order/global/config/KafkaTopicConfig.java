package com.commerce.order.global.config;

import com.commerce.order.messaging.OrderEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(OrderEventPublisher.ORDER_EVENTS_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}