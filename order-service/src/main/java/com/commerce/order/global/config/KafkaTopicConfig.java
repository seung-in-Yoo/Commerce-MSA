package com.commerce.order.global.config;

import com.commerce.order.messaging.SagaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    // 오케스트레이션 채널 5개
    // 같은 주문의 메시지들이 순서를 지키도록 파티션은 1개
    @Bean
    public NewTopic paymentCommandsTopic() {
        return TopicBuilder.name(SagaTopics.PAYMENT_COMMANDS)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentRepliesTopic() {
        return TopicBuilder.name(SagaTopics.PAYMENT_REPLIES)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentRefundCommandsTopic() {
        return TopicBuilder.name(SagaTopics.PAYMENT_REFUND_COMMANDS)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic stockCommandsTopic() {
        return TopicBuilder.name(SagaTopics.STOCK_COMMANDS)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic stockRepliesTopic() {
        return TopicBuilder.name(SagaTopics.STOCK_REPLIES)
                .partitions(1)
                .replicas(1)
                .build();
    }
}