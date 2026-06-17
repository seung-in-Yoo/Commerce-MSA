package com.commerce.product.messaging;

import com.commerce.product.dto.StockDeductRequest;
import com.commerce.product.dto.StockDeductResponse;
import com.commerce.product.exception.ProductErrorCase;
import com.commerce.product.global.exception.ApplicationException;
import com.commerce.product.messaging.command.DeductStockCommand;
import com.commerce.product.messaging.inbox.ProcessedMessage;
import com.commerce.product.messaging.inbox.ProcessedMessageRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StockCommandHandler 단위 테스트")
class StockCommandHandlerTest {

    @InjectMocks
    private StockCommandHandler stockCommandHandler;

    @Mock
    private ProductService productService;

    @Mock
    private ProcessedMessageRepository processedMessageRepository;

    private static DeductStockCommand defaultCommand() {
        return new DeductStockCommand("msg-1", 1L, List.of(
                new DeductStockCommand.Item(1L, 2),
                new DeductStockCommand.Item(3L, 1)));
    }

    @Nested
    @DisplayName("deductAndRecord")
    class DeductAndRecord {

        @Test
        @DisplayName("성공 - 차감 후 inbox 기록, 명령 항목을 요청으로 매핑")
        void deducts_andRecordsInbox() {
            given(processedMessageRepository.existsById("msg-1")).willReturn(false);
            given(productService.deductStock(any())).willReturn(StockDeductResponse.builder()
                    .items(List.of(StockDeductResponse.Item.builder()
                            .productId(1L).productName("키보드").unitPrice(30000L).quantity(2).build()))
                    .build());

            Optional<StockDeductResponse> result = stockCommandHandler.deductAndRecord(defaultCommand());

            assertThat(result).isPresent();

            ArgumentCaptor<StockDeductRequest> reqCaptor = ArgumentCaptor.forClass(StockDeductRequest.class);
            then(productService).should().deductStock(reqCaptor.capture());
            assertThat(reqCaptor.getValue().items())
                    .extracting(StockDeductRequest.Line::productId).containsExactly(1L, 3L);

            then(processedMessageRepository).should().save(any(ProcessedMessage.class));
        }

        @Test
        @DisplayName("멱등 - 이미 처리한 messageId면 차감/기록 없이 empty 반환")
        void duplicate_returnsEmpty() {
            given(processedMessageRepository.existsById("msg-1")).willReturn(true);

            Optional<StockDeductResponse> result = stockCommandHandler.deductAndRecord(defaultCommand());

            assertThat(result).isEmpty();
            then(productService).should(never()).deductStock(any());
            then(processedMessageRepository).should(never()).save(any(ProcessedMessage.class));
        }

        @Test
        @DisplayName("실패 - 재고 부족이면 예외 전파(트랜잭션 롤백), inbox 미기록")
        void outOfStock_throws_andDoesNotRecord() {
            given(processedMessageRepository.existsById("msg-1")).willReturn(false);
            given(productService.deductStock(any()))
                    .willThrow(ApplicationException.from(ProductErrorCase.OUT_OF_STOCK));

            assertThatThrownBy(() -> stockCommandHandler.deductAndRecord(defaultCommand()))
                    .isInstanceOf(ApplicationException.class)
                    .extracting("errorCase").isEqualTo(ProductErrorCase.OUT_OF_STOCK);

            then(processedMessageRepository).should(never()).save(any(ProcessedMessage.class));
        }
    }
}