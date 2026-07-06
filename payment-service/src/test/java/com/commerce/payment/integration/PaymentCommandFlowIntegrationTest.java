package com.commerce.payment.integration;

import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.messaging.PaymentReplyPublisher;
import com.commerce.payment.messaging.command.ProcessPaymentCommand;
import com.commerce.payment.messaging.reply.PaymentProcessedReply;
import com.commerce.payment.repository.PaymentRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// payment-commands 소비 → payment-replies 발행 검증
// 메시지를 발행하면 payment 앱이 언젠가 소비해 응답을 발행 (비동기)
@SpringBootTest
@Testcontainers
@DisplayName("Payment 결제 명령 처리 통합 테스트")
class PaymentCommandFlowIntegrationTest {

    private static final String PAYMENT_COMMANDS_TOPIC = "payment-commands";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    // 이미 구성해둔 KafkaTemplate 재사용
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    @DisplayName("성공 - 한도 이하 결제 명령 → payment-replies 에 APPROVED 응답 발행 + Payment 저장")
    void approved_command_publishes_approved_reply() {
        // 한도 이하 금액
        Long orderId = 1001L;
        long amount = 50_000L;

        // payment-commands 로 결제 명령 발행
        kafkaTemplate.send(PAYMENT_COMMANDS_TOPIC, String.valueOf(orderId),
                new ProcessPaymentCommand(UUID.randomUUID().toString(), orderId, amount));

        // payment 가 소비 → APPROVED 응답을 payment-replies 로 발행할 때까지 기다렸다 검증
        PaymentProcessedReply reply = awaitReplyFor(orderId);

        assertThat(reply.result()).isEqualTo(PaymentProcessedReply.Result.APPROVED);
        assertThat(reply.paymentId()).isNotNull();
        assertThat(reply.reasonCode()).isNull();

        // MySQL 에 결제가 APPROVED 로 저장 (Kafka + DB 함께 동작)
        assertThat(paymentRepository.findById(reply.paymentId()))
                .get()
                .satisfies(payment -> {
                    assertThat(payment.getOrderId()).isEqualTo(orderId);
                    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
                });
    }

    @Test
    @DisplayName("성공 - 한도 초과 결제 명령 → payment-replies 에 FAILED 응답 발행")
    void over_limit_command_publishes_failed_reply() {
        // 한도 초과 금액
        Long orderId = 1002L;
        long amount = 2_000_000L;

        kafkaTemplate.send(PAYMENT_COMMANDS_TOPIC, String.valueOf(orderId),
                new ProcessPaymentCommand(UUID.randomUUID().toString(), orderId, amount));

        // FAILED 응답이 발행
        PaymentProcessedReply reply = awaitReplyFor(orderId);

        assertThat(reply.result()).isEqualTo(PaymentProcessedReply.Result.FAILED);
        assertThat(reply.reasonCode()).isEqualTo("PAYMENT_LIMIT_EXCEEDED");
    }

    // payment-replies 를 구독해, 주어진 orderId 의 응답이 올 때까지 최대 20초 poll
    private PaymentProcessedReply awaitReplyFor(Long orderId) {
        JsonDeserializer<PaymentProcessedReply> valueDeserializer =
                new JsonDeserializer<>(PaymentProcessedReply.class, false);
        valueDeserializer.addTrustedPackages("*");

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-reply-consumer-" + orderId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        List<PaymentProcessedReply> received = new ArrayList<>();
        try (KafkaConsumer<String, PaymentProcessedReply> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), valueDeserializer)) {
            consumer.subscribe(List.of(PaymentReplyPublisher.PAYMENT_REPLIES_TOPIC));

            return await()
                    .atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofMillis(300))
                    .until(() -> {
                        consumer.poll(Duration.ofMillis(300))
                                .forEach(record -> received.add(record.value()));
                        return received.stream()
                                .filter(reply -> reply.orderId().equals(orderId))
                                .findFirst()
                                .orElse(null);
                    }, Objects::nonNull);
        }
    }
}