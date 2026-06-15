package com.commerce.product.messaging;

import com.commerce.product.dto.StockDeductRequest;
import com.commerce.product.dto.StockDeductResponse;
import com.commerce.product.exception.ProductErrorCase;
import com.commerce.product.fixture.PaymentEventFixture;
import com.commerce.product.global.exception.ApplicationException;
import com.commerce.product.messaging.event.StockProcessedEvent;
import com.commerce.product.service.ProductService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PaymentEventListener 단위 테스트")
class PaymentEventListenerTest {

    @InjectMocks
    private PaymentEventListener paymentEventListener;

    @Mock
    private ProductService productService;

    @Mock
    private ProductEventPublisher productEventPublisher;

    @Nested
    @DisplayName("onPaymentProcessed")
    class OnPaymentProcessed {

        @Test
        @DisplayName("성공 - APPROVED면 차감 후 StockProcessed(DEDUCTED, 이름/단가 포함) 발행")
        void approved_deductsAndPublishesDeducted() {
            given(productService.deductStock(any())).willReturn(StockDeductResponse.builder()
                    .items(List.of(
                            StockDeductResponse.Item.builder()
                                    .productId(1L).productName("키보드").unitPrice(30000L).quantity(2).build(),
                            StockDeductResponse.Item.builder()
                                    .productId(3L).productName("컴퓨터").unitPrice(600000L).quantity(1).build()))
                    .build());

            paymentEventListener.onPaymentProcessed(PaymentEventFixture.approvedEvent());

            ArgumentCaptor<StockDeductRequest> reqCaptor = ArgumentCaptor.forClass(StockDeductRequest.class);
            then(productService).should().deductStock(reqCaptor.capture());
            assertThat(reqCaptor.getValue().items())
                    .extracting(StockDeductRequest.Line::productId).containsExactly(1L, 3L);

            ArgumentCaptor<StockProcessedEvent> evtCaptor = ArgumentCaptor.forClass(StockProcessedEvent.class);
            then(productEventPublisher).should().publishStockProcessed(evtCaptor.capture());
            StockProcessedEvent published = evtCaptor.getValue();
            assertThat(published.orderId()).isEqualTo(1L);
            assertThat(published.result()).isEqualTo(StockProcessedEvent.Result.DEDUCTED);
            assertThat(published.items())
                    .extracting(StockProcessedEvent.Item::productName).containsExactly("키보드", "컴퓨터");
        }

        @Test
        @DisplayName("성공 - FAILED면 재고를 건드리지 않고 무시(차감/발행 없음)")
        void failed_ignored() {
            paymentEventListener.onPaymentProcessed(PaymentEventFixture.failedEvent());

            then(productService).should(never()).deductStock(any());
            then(productEventPublisher).should(never()).publishStockProcessed(any());
        }

        @Test
        @DisplayName("실패 - 차감이 OUT_OF_STOCK을 던지면 전파 대신 StockProcessed(FAILED) 발행")
        void deductFails_publishesFailed() {
            given(productService.deductStock(any()))
                    .willThrow(ApplicationException.from(ProductErrorCase.OUT_OF_STOCK));

            assertThatCode(() -> paymentEventListener.onPaymentProcessed(PaymentEventFixture.approvedEvent()))
                    .doesNotThrowAnyException();

            ArgumentCaptor<StockProcessedEvent> evtCaptor = ArgumentCaptor.forClass(StockProcessedEvent.class);
            then(productEventPublisher).should().publishStockProcessed(evtCaptor.capture());
            StockProcessedEvent published = evtCaptor.getValue();
            assertThat(published.result()).isEqualTo(StockProcessedEvent.Result.FAILED);
            assertThat(published.reasonCode()).isEqualTo(ProductErrorCase.OUT_OF_STOCK.getCode());
            assertThat(published.items()).isEmpty();
        }
    }
}