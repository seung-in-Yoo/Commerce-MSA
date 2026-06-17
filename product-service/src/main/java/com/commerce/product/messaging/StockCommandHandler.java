package com.commerce.product.messaging;

import com.commerce.product.dto.StockDeductRequest;
import com.commerce.product.dto.StockDeductResponse;
import com.commerce.product.messaging.command.DeductStockCommand;
import com.commerce.product.messaging.inbox.ProcessedMessage;
import com.commerce.product.messaging.inbox.ProcessedMessageRepository;
import com.commerce.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// 재고 차감의 멱등 처리 단위
// 재고 차감(deductStock)과 inbox 기록(ProcessedMessage)을 한 트랜잭션으로 묶음
@Component
@RequiredArgsConstructor
public class StockCommandHandler {

    private final ProductService productService;
    private final ProcessedMessageRepository processedMessageRepository;

    // 중복(이미 처리한 messageId) -> Optional.empty()
    // 차감 성공                  -> Optional.of(결과)
    // 재고 부족/상품 없음        -> ApplicationException 그대로 (트랜잭션 롤백, inbox 미기록)
    @Transactional
    public Optional<StockDeductResponse> deductAndRecord(DeductStockCommand command) {
        // 멱등 가드: 이미 처리한 messageId면(= 재배달) 차감하지 않고 스킵
        if (processedMessageRepository.existsById(command.messageId())) {
            return Optional.empty();
        }

        StockDeductRequest request = new StockDeductRequest(
                command.items().stream()
                        .map(item -> new StockDeductRequest.Line(item.productId(), item.quantity()))
                        .toList());

        StockDeductResponse response = productService.deductStock(request);
        // 차감 부수효과와 같은 트랜잭션에서 처리 사실을 기록
        processedMessageRepository.save(ProcessedMessage.of(command.messageId()));
        return Optional.of(response);
    }
}