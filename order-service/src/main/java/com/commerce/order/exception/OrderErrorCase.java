package com.commerce.order.exception;

import com.commerce.order.global.exception.ErrorCase;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCase implements ErrorCase {

    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_001", "존재하지 않는 주문입니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_002", "주문하려는 상품을 찾을 수 없습니다."),
    PRODUCT_OUT_OF_STOCK(HttpStatus.CONFLICT, "ORDER_003", "상품 재고가 부족합니다."),
    PRODUCT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ORDER_004",
            "상품 서비스를 호출할 수 없습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}