package com.commerce.order.messaging;

import com.commerce.order.domain.ProductSnapshot;
import com.commerce.order.fixture.StockProcessedEventFixture;
import com.commerce.order.service.OrderService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderResultListener 단위 테스트")
class OrderResultListenerTest {

    @InjectMocks
    private OrderResultListener orderResultListener;

    @Mock
    private OrderService orderService;

    @Nested
    @DisplayName("onStockProcessed")
    class OnStockProcessed {

        @Test
        @DisplayName("성공(DEDUCTED) - 이름/단가 스냅샷으로 confirmOrder 호출, cancel은 호출 안 함")
        void deducted_confirms() {
            orderResultListener.onStockProcessed(StockProcessedEventFixture.deducted());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ProductSnapshot>> captor = ArgumentCaptor.forClass(List.class);
            then(orderService).should().confirmOrder(eq(1L), captor.capture());
            assertThat(captor.getValue())
                    .extracting(ProductSnapshot::productName).containsExactly("키보드");
            then(orderService).should(never()).cancelOrder(any());
        }

        @Test
        @DisplayName("실패(FAILED) - cancelOrder 호출, confirm은 호출 안 함")
        void failed_cancels() {
            orderResultListener.onStockProcessed(StockProcessedEventFixture.failed());

            then(orderService).should().cancelOrder(1L);
            then(orderService).should(never()).confirmOrder(any(), any());
        }
    }
}
