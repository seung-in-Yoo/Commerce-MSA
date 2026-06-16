package com.commerce.product.messaging;

import com.commerce.product.dto.StockDeductRequest;
import com.commerce.product.dto.StockDeductResponse;
import com.commerce.product.exception.ProductErrorCase;
import com.commerce.product.global.exception.ApplicationException;
import com.commerce.product.messaging.command.DeductStockCommand;
import com.commerce.product.messaging.reply.StockProcessedReply;
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
@DisplayName("StockCommandListener 단위 테스트")
class StockCommandListenerTest {

    @InjectMocks
    private StockCommandListener stockCommandListener;

    @Mock
    private ProductService productService;

    @Mock
    private StockReplyPublisher stockReplyPublisher;

    private static DeductStockCommand defaultCommand() {
        return new DeductStockCommand(1L, List.of(
                new DeductStockCommand.Item(1L, 2),
                new DeductStockCommand.Item(3L, 1)));
    }

    @Nested
    @DisplayName("onDeductStock")
    class OnDeductStock {

        @Test
        @DisplayName("성공 - 차감 후 StockProcessed(DEDUCTED, 이름/단가 포함) 응답 발행")
        void deducts_publishesDeducted() {
            given(productService.deductStock(any())).willReturn(StockDeductResponse.builder()
                    .items(List.of(
                            StockDeductResponse.Item.builder()
                                    .productId(1L).productName("키보드").unitPrice(30000L).quantity(2).build(),
                            StockDeductResponse.Item.builder()
                                    .productId(3L).productName("컴퓨터").unitPrice(600000L).quantity(1).build()))
                    .build());

            stockCommandListener.onDeductStock(defaultCommand());

            ArgumentCaptor<StockDeductRequest> reqCaptor = ArgumentCaptor.forClass(StockDeductRequest.class);
            then(productService).should().deductStock(reqCaptor.capture());
            assertThat(reqCaptor.getValue().items())
                    .extracting(StockDeductRequest.Line::productId).containsExactly(1L, 3L);

            ArgumentCaptor<StockProcessedReply> replyCaptor = ArgumentCaptor.forClass(StockProcessedReply.class);
            then(stockReplyPublisher).should().publishStockProcessed(replyCaptor.capture());
            StockProcessedReply reply = replyCaptor.getValue();
            assertThat(reply.orderId()).isEqualTo(1L);
            assertThat(reply.result()).isEqualTo(StockProcessedReply.Result.DEDUCTED);
            assertThat(reply.items())
                    .extracting(StockProcessedReply.Item::productName).containsExactly("키보드", "컴퓨터");
        }

        @Test
        @DisplayName("실패 - 차감이 OUT_OF_STOCK을 던지면 전파 대신 StockProcessed(FAILED) 응답 발행")
        void deductFails_publishesFailed() {
            given(productService.deductStock(any()))
                    .willThrow(ApplicationException.from(ProductErrorCase.OUT_OF_STOCK));

            assertThatCode(() -> stockCommandListener.onDeductStock(defaultCommand()))
                    .doesNotThrowAnyException();

            ArgumentCaptor<StockProcessedReply> replyCaptor = ArgumentCaptor.forClass(StockProcessedReply.class);
            then(stockReplyPublisher).should().publishStockProcessed(replyCaptor.capture());
            StockProcessedReply reply = replyCaptor.getValue();
            assertThat(reply.result()).isEqualTo(StockProcessedReply.Result.FAILED);
            assertThat(reply.reasonCode()).isEqualTo(ProductErrorCase.OUT_OF_STOCK.getCode());
            assertThat(reply.items()).isEmpty();
        }
    }
}