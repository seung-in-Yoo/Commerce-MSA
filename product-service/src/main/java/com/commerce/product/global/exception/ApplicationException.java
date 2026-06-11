package com.commerce.product.global.exception;

import lombok.Getter;

@Getter
public class ApplicationException extends RuntimeException {

    private final ErrorCase errorCase;

    private ApplicationException(ErrorCase errorCase) {
        super(errorCase.getMessage());
        this.errorCase = errorCase;
    }

    public static ApplicationException from(ErrorCase errorCase) {
        return new ApplicationException(errorCase);
    }
}