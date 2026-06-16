package com.commerce.product.global.config;

import com.commerce.product.messaging.StockReplyPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    // product가 재고 결과를 돌려보내는 reply 토픽
    @Bean
    public NewTopic stockRepliesTopic() {
        return TopicBuilder.name(StockReplyPublisher.STOCK_REPLIES_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}