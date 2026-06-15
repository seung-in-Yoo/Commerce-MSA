package com.commerce.product.messaging;

import com.commerce.product.dto.StockDeductRequest;
import com.commerce.product.dto.StockDeductResponse;
import com.commerce.product.exception.ProductErrorCase;
import com.commerce.product.fixture.OrderEventFixture;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderEventListener 단위 테스트")
class OrderEventListenerTest {

    @InjectMocks
    private OrderEventListener orderEventListener;

    @Mock
    private ProductService productService;

    @Mock
    private ProductEventPublisher productEventPublisher;

    @Nested
    @DisplayName("onOrderCreated")
    class OnOrderCreated {

        @Test
        @DisplayName("성공 - 차감 후 차감 결과로 StockProcessed(DEDUCTED, 이름/단가 포함) 발행")
        void success_publishesDeducted() {
            given(productService.deductStock(any())).willReturn(StockDeductResponse.builder()
                    .items(List.of(
                            StockDeductResponse.Item.builder()
                                    .productId(1L).productName("키보드").unitPrice(30000L).quantity(2).build(),
                            StockDeductResponse.Item.builder()
                                    .productId(3L).productName("컴퓨터").unitPrice(600000L).quantity(1).build()))
                    .build());

            orderEventListener.onOrderCreated(OrderEventFixture.defaultEvent());

            ArgumentCaptor<StockDeductRequest> reqCaptor = ArgumentCaptor.forClass(StockDeductRequest.class);
            then(productService).should().deductStock(reqCaptor.capture());
            assertThat(reqCaptor.getValue().items())
                    .extracting(StockDeductRequest.Line::productId).containsExactly(1L, 3L);

            ArgumentCaptor<StockProcessedEvent> evtCaptor = ArgumentCaptor.forClass(StockProcessedEvent.class);
            then(productEventPublisher).should().publishStockProcessed(evtCaptor.capture());
            StockProcessedEvent published = evtCaptor.getValue();
            assertThat(published.orderId()).isEqualTo(1L);
            assertThat(published.result()).isEqualTo(StockProcessedEvent.Result.DEDUCTED);
            assertThat(published.reasonCode()).isNull();
            assertThat(published.items())
                    .extracting(StockProcessedEvent.Item::productName).containsExactly("키보드", "컴퓨터");
        }

        @Test
        @DisplayName("실패 - deductStock이 OUT_OF_STOCK을 던지면 전파 대신 StockProcessed(FAILED) 발행")
        void deductFails_publishesFailed() {
            given(productService.deductStock(any()))
                    .willThrow(ApplicationException.from(ProductErrorCase.OUT_OF_STOCK));

            assertThatCode(() -> orderEventListener.onOrderCreated(OrderEventFixture.defaultEvent()))
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