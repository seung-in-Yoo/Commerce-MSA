package com.commerce.order.global.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerProducerListener;

import java.util.HashMap;
import java.util.Map;

// outbox 릴레이 전용 발행 템플릿
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${outbox.producer.acks:all}")
    private String acks;
    @Value("${outbox.producer.linger-ms:0}")
    private int lingerMs;
    @Value("${outbox.producer.batch-size:16384}")
    private int batchSize;
    @Value("${outbox.producer.compression-type:none}")
    private String compressionType;

    @Bean
    public KafkaTemplate<String, String> outboxKafkaTemplate(MeterRegistry meterRegistry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, acks);
        props.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compressionType);

        DefaultKafkaProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<>(props);
        // MicrometerProducerListener를 직접 등록해 producer 계측을 Prometheus로 내보냄
        producerFactory.addListener(new MicrometerProducerListener<>(meterRegistry));

        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        // 분산추적: 발행 시 현재 trace context를 Kafka 헤더에 실어 보낸다
        template.setObservationEnabled(true);
        return template;
    }
}