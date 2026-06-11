package com.commerce.order.global.exception;

import org.springframework.http.HttpStatus;

public interface ErrorCase {

    HttpStatus getHttpStatus();

    String getCode();

    String getMessage();
}