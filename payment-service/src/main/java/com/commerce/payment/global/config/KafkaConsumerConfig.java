package com.commerce.payment.global.config;

import com.commerce.payment.messaging.command.ProcessPaymentCommand;
import com.commerce.payment.messaging.command.RefundPaymentCommand;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

// payment는 타입이 다른 두 command 토픽을 구독
// payment-commands        : ProcessPaymentCommand (결제)
// payment-refund-commands : RefundPaymentCommand  (환불 보상)
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> typedFactory(Class<T> type) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);

        JsonDeserializer<T> valueDeserializer = new JsonDeserializer<>(type, false);
        valueDeserializer.addTrustedPackages("*");

        DefaultKafkaConsumerFactory<String, T> consumerFactory = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), valueDeserializer);

        ConcurrentKafkaListenerContainerFactory<String, T> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // 분산추적: 수신 시 Kafka 헤더의 trace context를 이어받아 같은 trace로 묶는다
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ProcessPaymentCommand> processPaymentCommandListenerFactory() {
        return typedFactory(ProcessPaymentCommand.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, RefundPaymentCommand> refundPaymentCommandListenerFactory() {
        return typedFactory(RefundPaymentCommand.class);
    }
}