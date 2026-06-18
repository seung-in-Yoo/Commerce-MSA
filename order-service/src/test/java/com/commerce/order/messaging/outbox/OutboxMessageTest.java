package com.commerce.order.messaging.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutboxMessage 단위 테스트")
class OutboxMessageTest {

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("성공 - 생성 시 id 발급 + PENDING 상태 + sentAt은 null + traceContext 보관")
        void success() {
            OutboxMessage message = OutboxMessage.create("payment-commands", "1", "{\"orderId\":1}", "{\"traceparent\":\"00-abc-def-01\"}");

            assertThat(message.getId()).isNotBlank();
            assertThat(message.getTopic()).isEqualTo("payment-commands");
            assertThat(message.getMessageKey()).isEqualTo("1");
            assertThat(message.getPayload()).isEqualTo("{\"orderId\":1}");
            assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(message.getCreatedAt()).isNotNull();
            assertThat(message.getSentAt()).isNull();
            assertThat(message.getTraceContext()).isEqualTo("{\"traceparent\":\"00-abc-def-01\"}");
        }
    }

    @Nested
    @DisplayName("markSent")
    class MarkSent {

        @Test
        @DisplayName("성공 - 상태가 SENT로 바뀌고 sentAt이 채워진다")
        void success() {
            OutboxMessage message = OutboxMessage.create("payment-commands", "1", "{\"orderId\":1}", null);

            message.markSent();

            assertThat(message.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(message.getSentAt()).isNotNull();
        }
    }
}