package com.commerce.order.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OutboxRelay 단위 테스트")
class OutboxRelayTest {

    @InjectMocks
    private OutboxRelay outboxRelay;

    @Mock
    private OutboxMessageRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> outboxKafkaTemplate;

    @Mock
    private Tracer tracer;

    @Mock
    private Propagator propagator;

    @Mock
    private ObjectMapper objectMapper;

    @Nested
    @DisplayName("publishPending")
    class PublishPending {

        @Test
        @DisplayName("성공 - PENDING 2건을 각각 발행하고 SENT로 마킹")
        void success() {
            OutboxMessage m1 = OutboxMessage.create("payment-commands", "1", "{\"orderId\":1}", null);
            OutboxMessage m2 = OutboxMessage.create("stock-commands", "2", "{\"orderId\":2}", null);
            given(outboxRepository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                    .willReturn(List.of(m1, m2));
            given(outboxKafkaTemplate.send(anyString(), anyString(), anyString()))
                    .willReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

            outboxRelay.publishPending();

            then(outboxKafkaTemplate).should().send("payment-commands", "1", "{\"orderId\":1}");
            then(outboxKafkaTemplate).should().send("stock-commands", "2", "{\"orderId\":2}");
            assertThat(m1.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(m2.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(m1.getSentAt()).isNotNull();
        }

        @Test
        @DisplayName("성공 - PENDING이 없으면 아무것도 발행하지 않는다")
        void empty() {
            given(outboxRepository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                    .willReturn(List.of());

            outboxRelay.publishPending();

            then(outboxKafkaTemplate).should(never()).send(anyString(), anyString(), anyString());
        }
    }
}