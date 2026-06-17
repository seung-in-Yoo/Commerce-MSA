package com.commerce.product.messaging;

import com.commerce.product.dto.StockDeductResponse;
import com.commerce.product.global.exception.ApplicationException;
import com.commerce.product.messaging.command.DeductStockCommand;
import com.commerce.product.messaging.reply.StockProcessedReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

// stock-commands 토픽 구독자
// 오케스트레이터의 DeductStockCommand를 받아 재고를 차감하고, 결과를 stock-replies로 응답
@Slf4j
@Component
@RequiredArgsConstructor
public class StockCommandListener {

    private final StockCommandHandler stockCommandHandler;
    private final StockReplyPublisher stockReplyPublisher;

    // product는 타입 하나(DeductStockCommand)만 받으므로 containerFactory 불필요
    // 트랜잭션 경계는 StockCommandHandler가 갖고, 여기선 응답 발행만 (예외를 트랜잭션 밖에서 번역)
    @KafkaListener(topics = "stock-commands")
    public void onDeductStock(DeductStockCommand command) {
        log.info("[product] DeductStock 명령 수신 <- messageId={}, orderId={}, items={}",
                command.messageId(), command.orderId(), command.items());

        try {
            Optional<StockDeductResponse> result = stockCommandHandler.deductAndRecord(command);

            if (result.isEmpty()) {
                // 멱등 가드에 걸린 중복 -> 재고 차감/응답 모두 스킵
                log.info("[product] 중복 메시지 스킵(이미 처리됨) -> messageId={}, orderId={}",
                        command.messageId(), command.orderId());
                return;
            }

            // 차감 성공 -> 차감된 상품의 이름/단가를 실어 DEDUCTED 응답 -> order가 주문 확정
            stockReplyPublisher.publishStockProcessed(
                    StockProcessedReply.deducted(command.orderId(), toReplyItems(result.get())));
            log.info("[product] 재고 차감 완료 -> orderId={}", command.orderId());
        } catch (ApplicationException e) {
            // 차감 실패(재고 부족/상품 없음) -> 삼키지 않고 FAILED 응답 -> order가 보상(취소+환불)
            // (실패는 영속 효과가 없어 inbox에 기록하지 않는다 -> 재배달돼도 다시 실패할 뿐, 이중차감 없음)
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