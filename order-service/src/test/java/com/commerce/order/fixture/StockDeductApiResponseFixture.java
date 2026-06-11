package com.commerce.order.fixture;

import com.commerce.order.global.client.dto.StockDeductApiResponse;

import java.util.List;

public class StockDeductApiResponseFixture {

    public static List<StockDeductApiResponse.Item> defaultItems() {
        return List.of(
                new StockDeductApiResponse.Item(1L, "키보드", 30000L, 2),
                new StockDeductApiResponse.Item(3L, "컴퓨터", 600000L, 1));
    }
}