package com.commerce.product.exception;

import com.commerce.product.global.exception.ErrorCase;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProductErrorCase implements ErrorCase {

    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT_001", "존재하지 않는 상품입니다."),
    OUT_OF_STOCK(HttpStatus.CONFLICT, "PRODUCT_002", "재고가 부족합니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}