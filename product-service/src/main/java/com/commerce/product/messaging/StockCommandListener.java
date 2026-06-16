package com.commerce.product.messaging;

import com.commerce.product.dto.StockDeductRequest;
import com.commerce.product.dto.StockDeductResponse;
import com.commerce.product.global.exception.ApplicationException;
import com.commerce.product.messaging.command.DeductStockCommand;
import com.commerce.product.messaging.reply.StockProcessedReply;
import com.commerce.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

// stock-commands 토픽 구독자
// 오케스트레이터의 DeductStockCommand를 받아 재고를 차감하고, 결과를 stock-replies로 응답
@Slf4j
@Component
@RequiredArgsConstructor
public class StockCommandListener {

    private final ProductService productService;
    private final StockReplyPublisher stockReplyPublisher;

    // product는 타입 하나(DeductStockCommand)만 받으므로 containerFactory 불필요
    @KafkaListener(topics = "stock-commands")
    public void onDeductStock(DeductStockCommand command) {
        log.info("[product] DeductStock 명령 수신 <- orderId={}, items={}", command.orderId(), command.items());

        StockDeductRequest request = new StockDeductRequest(
                command.items().stream()
                        .map(item -> new StockDeductRequest.Line(item.productId(), item.quantity()))
                        .toList());

        try {
            StockDeductResponse response = productService.deductStock(request);
            // 차감 성공 -> 차감된 상품의 이름/단가를 실어 DEDUCTED 응답 -> order가 주문 확정
            stockReplyPublisher.publishStockProcessed(
                    StockProcessedReply.deducted(command.orderId(), toReplyItems(response)));
            log.info("[product] 재고 차감 완료 -> orderId={}", command.orderId());
        } catch (ApplicationException e) {
            // 차감 실패(재고 부족/상품 없음) -> 삼키지 않고 FAILED 응답 -> order가 보상(취소+환불)
            log.warn("[product] 재고 차감 실패 -> orderId={}, code={} -> StockProcessed(FAILED) 응답",
                    command.orderId(), e.getErrorCase().getCode());
            stockReplyPublisher.publishStockProcessed(
                    StockProcessedReply.failed(command.orderId(), e.getErrorCase().getCode()));
        }
    }

    private List<StockProcessedReply.Item> toReplyItems(StockDeductResponse response) {
        return response.getItems().stream()
                .map(item -> new StockProcessedReply.Item(
                        item.getProductId(), item.getProductName(), item.getUnitPrice()))
                .toList();
    }
}