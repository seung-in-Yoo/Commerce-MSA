package com.commerce.order.messaging;

import com.commerce.order.domain.Order;
import com.commerce.order.messaging.command.ProcessPaymentCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderSagaOrchestrator 단위 테스트")
class OrderSagaOrchestratorTest {

    @InjectMocks
    private OrderSagaOrchestrator orchestrator;

    @Mock
    private SagaCommandPublisher commandPublisher;

    @Nested
    @DisplayName("start")
    class Start {

        @Test
        @DisplayName("성공 - 주문 금액으로 ProcessPayment 명령 발행")
        void success() {
            Order order = mock(Order.class);
            given(order.getId()).willReturn(1L);
            given(order.getTotalAmount()).willReturn(60000L);

            orchestrator.start(order);

            ArgumentCaptor<ProcessPaymentCommand> captor = ArgumentCaptor.forClass(ProcessPaymentCommand.class);
            then(commandPublisher).should().sendProcessPayment(captor.capture());
            ProcessPaymentCommand command = captor.getValue();
            assertThat(command.orderId()).isEqualTo(1L);
            assertThat(command.amount()).isEqualTo(60000L);
        }
    }
}