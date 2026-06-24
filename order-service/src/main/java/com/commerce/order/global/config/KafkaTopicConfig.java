package com.commerce.order.global.config;

import com.commerce.order.messaging.SagaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    // payment-commands(병목 토픽)의 파티션 수 = 병렬 처리 차선 수
    // 기본 1 -> 부하실험 때만 늘린다(ex: 4)
    private final int paymentCommandsPartitions;

    public KafkaTopicConfig(
            @Value("${saga.payment-commands.partitions:1}") int paymentCommandsPartitions) {
        this.paymentCommandsPartitions = paymentCommandsPartitions;
    }

    @Bean
    public NewTopic paymentCommandsTopic() {
        return TopicBuilder.name(SagaTopics.PAYMENT_COMMANDS)
                .partitions(paymentCommandsPartitions)
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