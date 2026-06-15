package com.commerce.order.global.config;

import com.commerce.order.messaging.event.PaymentProcessedEvent;
import com.commerce.order.messaging.event.StockProcessedEvent;
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

// order는 이제 두 종류의 결과 이벤트를 구독(Saga 보상)
// product-events : StockProcessed     (재고 결과 -> CONFIRMED/CANCELLED)
// payment-events : PaymentProcessed   (결제 결과 -> FAILED면 즉시 CANCELLED)
// -> 타입별 전용 컨테이너 팩토리를 만들어 @KafkaListener로 분리
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
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, StockProcessedEvent> stockProcessedListenerFactory() {
        return typedFactory(StockProcessedEvent.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentProcessedEvent> paymentProcessedListenerFactory() {
        return typedFactory(PaymentProcessedEvent.class);
    }
}