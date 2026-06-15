package com.commerce.product.global.config;

import com.commerce.product.messaging.ProductEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic productEventsTopic() {
        return TopicBuilder.name(ProductEventPublisher.PRODUCT_EVENTS_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}