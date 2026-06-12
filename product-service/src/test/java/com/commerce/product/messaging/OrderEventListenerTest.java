package com.commerce.product.messaging;

import com.commerce.product.dto.StockDeductRequest;
import com.commerce.product.exception.ProductErrorCase;
import com.commerce.product.fixture.OrderEventFixture;
import com.commerce.product.global.exception.ApplicationException;
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

    @Nested
    @DisplayName("onOrderCreated")
    class OnOrderCreated {

        @Test
        @DisplayName("성공 - 이벤트 항목을 재고 차감 요청으로 변환해 deductStock 호출")
        void success() {
            orderEventListener.onOrderCreated(OrderEventFixture.defaultEvent());

            ArgumentCaptor<StockDeductRequest> captor = ArgumentCaptor.forClass(StockDeductRequest.class);
            then(productService).should().deductStock(captor.capture());
            assertThat(captor.getValue().items()).hasSize(2)
                    .extracting(StockDeductRequest.Line::productId).containsExactly(1L, 3L);
        }

        @Test
        @DisplayName("실패 - deductStock이 OUT_OF_STOCK을 던져도 예외를 삼켜 전파하지 않음(불일치 관찰)")
        void deductFails_swallowed() {
            given(productService.deductStock(any()))
                    .willThrow(ApplicationException.from(ProductErrorCase.OUT_OF_STOCK));

            assertThatCode(() -> orderEventListener.onOrderCreated(OrderEventFixture.defaultEvent()))
                    .doesNotThrowAnyException();
            then(productService).should().deductStock(any());
        }
    }
}