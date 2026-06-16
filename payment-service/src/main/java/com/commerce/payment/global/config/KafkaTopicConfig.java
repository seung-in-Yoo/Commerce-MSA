package com.commerce.payment.global.config;

import com.commerce.payment.messaging.PaymentReplyPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    // payment가 결과를 돌려보내는 reply 토픽
    @Bean
    public NewTopic paymentRepliesTopic() {
        return TopicBuilder.name(PaymentReplyPublisher.PAYMENT_REPLIES_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}