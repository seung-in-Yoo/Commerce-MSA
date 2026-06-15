package com.commerce.order.fixture;

import com.commerce.order.messaging.event.StockProcessedEvent;

import java.util.List;

public class StockProcessedEventFixture {

    // orderId=1, 키보드 1종 차감 성공
    public static StockProcessedEvent deducted() {
        return new StockProcessedEvent(1L, StockProcessedEvent.Result.DEDUCTED,
                List.of(new StockProcessedEvent.Item(100L, "키보드", 30000L)), null);
    }

    // orderId=1, 재고 부족(PRODUCT_002)으로 실패
    public static StockProcessedEvent failed() {
        return new StockProcessedEvent(1L, StockProcessedEvent.Result.FAILED,
                List.of(), "PRODUCT_002");
    }
}