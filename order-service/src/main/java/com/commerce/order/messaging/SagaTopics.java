package com.commerce.order.messaging;

// 오케스트레이션 사가의 모든 채널(토픽) 이름을 한 곳에 모음
public final class SagaTopics {

    private SagaTopics() {
    }

    // 오케스트레이터 -> payment
    public static final String PAYMENT_COMMANDS = "payment-commands";

    // payment -> 오케스트레이터
    public static final String PAYMENT_REPLIES = "payment-replies";

    // 오케스트레이터 -> product
    public static final String STOCK_COMMANDS = "stock-commands";

    // product -> 오케스트레이터
    public static final String STOCK_REPLIES = "stock-replies";
}