package com.commerce.product.global.response;

import lombok.Getter;

@Getter
public class CommonResponse<T> {

    private final boolean success;
    private final String code;
    private final String message;
    private final T data;

    private CommonResponse(boolean success, String code, String message, T data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> CommonResponse<T> success(T data) {
        return new CommonResponse<>(true, "SUCCESS", "요청에 성공했습니다.", data);
    }

    public static CommonResponse<Void> error(String code, String message) {
        return new CommonResponse<>(false, code, message, null);
    }
}