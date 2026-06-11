package com.commerce.order.global.client.dto;

public record ApiResult<T>(
        boolean success,
        String code,
        String message,
        T data
) {
}