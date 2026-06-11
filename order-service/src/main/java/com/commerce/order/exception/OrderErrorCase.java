package com.commerce.order.exception;

import com.commerce.order.global.exception.ErrorCase;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCase implements ErrorCase {

    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_001", "존재하지 않는 주문입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}