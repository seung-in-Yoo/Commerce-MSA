package com.commerce.order.messaging.reply;

import java.util.List;

// product -> 오케스트레이터 (stock-replies 토픽)
// 재고 차감 결과
// messageId: 해당 응답 1건의 고유 식별자 (inbox(ProcessedMessage)에 기록해 중복 배달을 거름)
public record StockProcessedReply(
        String messageId,
        Long orderId,
        Result result,
        List<Item> items,   // DEDUCTED일 때 차감된 상품의 이름/단가 스냅샷
        String reasonCode   // FAILED일 때 실패 사유 코드
) {
    public enum Result {
        DEDUCTED,
        FAILED
    }

    public record Item(
            Long productId,
            String productName,
            long unitPrice
    ) {
    }
}