package com.commerce.order.messaging;

import com.commerce.order.messaging.command.DeductStockCommand;
import com.commerce.order.messaging.command.ProcessPaymentCommand;
import com.commerce.order.messaging.command.RefundPaymentCommand;
import com.commerce.order.messaging.outbox.OutboxMessage;
import com.commerce.order.messaging.outbox.OutboxMessageRepository;
import com.commerce.order.messaging.outbox.OutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SagaCommandPublisher 단위 테스트")
class SagaCommandPublisherTest {

    @Mock
    private OutboxMessageRepository outboxRepository;

    @Captor
    private ArgumentCaptor<OutboxMessage> outboxCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private Tracer tracer;

    @Mock
    private Propagator propagator;

    private SagaCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new SagaCommandPublisher(outboxRepository, objectMapper, tracer, propagator);
    }

    @Nested
    @DisplayName("sendProcessPayment")
    class SendProcessPayment {

        @Test
        @DisplayName("성공 - payment-commands 토픽으로 PENDING outbox 적재, 본문에 금액 직렬화")
        void success() throws Exception {
            ProcessPaymentCommand command = ProcessPaymentCommand.create(1L, 75000L);

            publisher.sendProcessPayment(command);

            then(outboxRepository).should().save(outboxCaptor.capture());
            OutboxMessage saved = outboxCaptor.getValue();
            assertThat(saved.getTopic()).isEqualTo(SagaTopics.PAYMENT_COMMANDS);
            assertThat(saved.getMessageKey()).isEqualTo("1");
            assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);

            ProcessPaymentCommand restored = objectMapper.readValue(saved.getPayload(), ProcessPaymentCommand.class);
            assertThat(restored.orderId()).isEqualTo(1L);
            assertThat(restored.amount()).isEqualTo(75000L);
            assertThat(restored.messageId()).isEqualTo(command.messageId());
        }
    }

    @Nested
    @DisplayName("sendDeductStock")
    class SendDeductStock {

        @Test
        @DisplayName("성공 - stock-commands 토픽으로 적재, 본문에 항목 목록 직렬화")
        void success() throws Exception {
            DeductStockCommand command = DeductStockCommand.create(2L,
                    List.of(new DeductStockCommand.Item(100L, 2)));

            publisher.sendDeductStock(command);

            then(outboxRepository).should().save(outboxCaptor.capture());
            OutboxMessage saved = outboxCaptor.getValue();
            assertThat(saved.getTopic()).isEqualTo(SagaTopics.STOCK_COMMANDS);
            assertThat(saved.getMessageKey()).isEqualTo("2");

            DeductStockCommand restored = objectMapper.readValue(saved.getPayload(), DeductStockCommand.class);
            assertThat(restored.orderId()).isEqualTo(2L);
            assertThat(restored.items()).hasSize(1)
                    .extracting(DeductStockCommand.Item::productId).containsExactly(100L);
        }
    }

    @Nested
    @DisplayName("sendRefundPayment")
    class SendRefundPayment {

        @Test
        @DisplayName("성공 - payment-refund-commands 토픽으로 적재")
        void success() throws Exception {
            RefundPaymentCommand command = RefundPaymentCommand.create(3L);

            publisher.sendRefundPayment(command);

            then(outboxRepository).should().save(outboxCaptor.capture());
            OutboxMessage saved = outboxCaptor.getValue();
            assertThat(saved.getTopic()).isEqualTo(SagaTopics.PAYMENT_REFUND_COMMANDS);
            assertThat(saved.getMessageKey()).isEqualTo("3");

            RefundPaymentCommand restored = objectMapper.readValue(saved.getPayload(), RefundPaymentCommand.class);
            assertThat(restored.orderId()).isEqualTo(3L);
        }
    }
}