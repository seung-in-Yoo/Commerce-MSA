package com.commerce.payment.exception;

import com.commerce.payment.global.exception.ErrorCase;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCase implements ErrorCase {

    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_001", "존재하지 않는 결제입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}