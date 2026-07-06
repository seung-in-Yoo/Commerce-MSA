package com.commerce.order.integration;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.dto.OrderResponse;
import com.commerce.order.fixture.OrderRequestFixture;
import com.commerce.order.messaging.SagaTopics;
import com.commerce.order.messaging.command.DeductStockCommand;
import com.commerce.order.messaging.command.ProcessPaymentCommand;
import com.commerce.order.messaging.command.RefundPaymentCommand;
import com.commerce.order.messaging.outbox.OutboxMessage;
import com.commerce.order.messaging.outbox.OutboxMessageRepository;
import com.commerce.order.messaging.outbox.OutboxStatus;
import com.commerce.order.messaging.reply.PaymentProcessedReply;
import com.commerce.order.messaging.reply.StockProcessedReply;
import com.commerce.order.repository.OrderRepository;
import com.commerce.order.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.serializer.JsonSerializer;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@DisplayName("Order 사가 오케스트레이션 통합 테스트")
class OrderSagaIntegrationTest {

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

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxMessageRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("성공 - 해피패스: 결제승인→재고차감 성공 → 명령 2건 Outbox 발행 + 주문 CONFIRMED")
    void happy_path_confirms_order() {
        // 주문 생성 -> 사가 시작(결제 명령을 outbox 에 PENDING 으로 적재)
        OrderResponse created = orderService.createOrder(OrderRequestFixture.defaultCreateRequest());
        Long orderId = created.getOrderId();
        long expectedAmount = 660_000L;

        // Outbox 릴레이가 payment-commands 로 실제 발행 -> 테스트가 payment로서 소비
        ProcessPaymentCommand paymentCmd = awaitCommandOn(
                SagaTopics.PAYMENT_COMMANDS, ProcessPaymentCommand.class, orderId, ProcessPaymentCommand::orderId);
        assertThat(paymentCmd.amount()).isEqualTo(expectedAmount);

        // outbox 행이 PENDING -> SENT 로 마킹
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(outboxRowFor(SagaTopics.PAYMENT_COMMANDS, orderId))
                        .get().extracting(OutboxMessage::getStatus).isEqualTo(OutboxStatus.SENT));

        // payment 승인 응답 주입
        sendReply(SagaTopics.PAYMENT_REPLIES, orderId, new PaymentProcessedReply(
                UUID.randomUUID().toString(), orderId, 999L, PaymentProcessedReply.Result.APPROVED, null));

        // 오케스트레이터가 재고 차감 명령을 결정/발행 -> 테스트가 product로서 소비
        DeductStockCommand stockCmd = awaitCommandOn(
                SagaTopics.STOCK_COMMANDS, DeductStockCommand.class, orderId, DeductStockCommand::orderId);
        assertThat(stockCmd.items())
                .extracting(DeductStockCommand.Item::productId)
                .containsExactlyInAnyOrder(1L, 3L);

        // 재고 차감 성공 응답 주입
        sendReply(SagaTopics.STOCK_REPLIES, orderId, new StockProcessedReply(
                UUID.randomUUID().toString(), orderId, StockProcessedReply.Result.DEDUCTED,
                List.of(new StockProcessedReply.Item(1L, "키보드", 30_000L),
                        new StockProcessedReply.Item(3L, "모니터", 600_000L)),
                null));

        // 최종 주문 상태가 CONFIRMED
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(orderRepository.findById(orderId))
                        .get().extracting(Order::getStatus).isEqualTo(OrderStatus.CONFIRMED));
    }

    @Test
    @DisplayName("성공 - 결제 거절 응답 → 주문 CANCELLED (재고 단계로 진행하지 않음)")
    void payment_declined_cancels_order() {
        OrderResponse created = orderService.createOrder(OrderRequestFixture.defaultCreateRequest());
        Long orderId = created.getOrderId();

        // 사가가 시작돼 결제 명령이 발행됐음을 확인
        awaitCommandOn(SagaTopics.PAYMENT_COMMANDS, ProcessPaymentCommand.class, orderId, ProcessPaymentCommand::orderId);

        // payment 거절 응답 주입
        sendReply(SagaTopics.PAYMENT_REPLIES, orderId, new PaymentProcessedReply(
                UUID.randomUUID().toString(), orderId, null, PaymentProcessedReply.Result.FAILED, "PAYMENT_LIMIT_EXCEEDED"));

        // 주문 취소
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(orderRepository.findById(orderId))
                        .get().extracting(Order::getStatus).isEqualTo(OrderStatus.CANCELLED));
    }

    @Test
    @DisplayName("성공 - 재고 실패 응답 → 결제 환불 명령 발행 + 주문 CANCELLED (보상 트랜잭션)")
    void stock_failed_triggers_refund_and_cancel() {
        OrderResponse created = orderService.createOrder(OrderRequestFixture.defaultCreateRequest());
        Long orderId = created.getOrderId();

        awaitCommandOn(SagaTopics.PAYMENT_COMMANDS, ProcessPaymentCommand.class, orderId, ProcessPaymentCommand::orderId);

        // 결제 승인 -> 오케스트레이터가 재고 차감 명령 발행
        sendReply(SagaTopics.PAYMENT_REPLIES, orderId, new PaymentProcessedReply(
                UUID.randomUUID().toString(), orderId, 999L, PaymentProcessedReply.Result.APPROVED, null));
        awaitCommandOn(SagaTopics.STOCK_COMMANDS, DeductStockCommand.class, orderId, DeductStockCommand::orderId);

        // 재고 실패 응답 주입
        sendReply(SagaTopics.STOCK_REPLIES, orderId, new StockProcessedReply(
                UUID.randomUUID().toString(), orderId, StockProcessedReply.Result.FAILED, null, "OUT_OF_STOCK"));

        // 이미 한 결제를 환불하라는 명령이 payment-refund-commands로 발행
        RefundPaymentCommand refundCmd = awaitCommandOn(
                SagaTopics.PAYMENT_REFUND_COMMANDS, RefundPaymentCommand.class, orderId, RefundPaymentCommand::orderId);
        assertThat(refundCmd.orderId()).isEqualTo(orderId);

        // 주문 취소
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(orderRepository.findById(orderId))
                        .get().extracting(Order::getStatus).isEqualTo(OrderStatus.CANCELLED));
    }

    private void sendReply(String topic, Long orderId, Object reply) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        try (KafkaProducer<String, Object> producer =
                     new KafkaProducer<>(props, new StringSerializer(), new JsonSerializer<>())) {
            producer.send(new ProducerRecord<>(topic, String.valueOf(orderId), reply));
            producer.flush();
        }
    }

    // commands 토픽을 구독해, 주어진 orderId 의 명령이 올 때까지 최대 25초 poll
    private <T> T awaitCommandOn(String topic, Class<T> type, Long orderId, Function<T, Long> orderIdOf) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-cmd-" + topic + "-" + orderId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        List<T> received = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            return await()
                    .atMost(Duration.ofSeconds(25))
                    .pollInterval(Duration.ofMillis(300))
                    .until(() -> {
                        consumer.poll(Duration.ofMillis(300))
                                .forEach(record -> received.add(parse(record.value(), type)));
                        return received.stream()
                                .filter(command -> orderIdOf.apply(command).equals(orderId))
                                .findFirst()
                                .orElse(null);
                    }, Objects::nonNull);
        }
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("command 파싱 실패: " + json, e);
        }
    }

    private Optional<OutboxMessage> outboxRowFor(String topic, Long orderId) {
        return outboxRepository.findAll().stream()
                .filter(m -> m.getTopic().equals(topic) && m.getMessageKey().equals(String.valueOf(orderId)))
                .findFirst();
    }
}