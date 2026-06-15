package com.commerce.payment.global.exception;

import org.springframework.http.HttpStatus;

public interface ErrorCase {

    HttpStatus getHttpStatus();

    String getCode();

    String getMessage();
}