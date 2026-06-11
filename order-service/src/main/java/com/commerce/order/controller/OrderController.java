package com.commerce.order.controller;

import com.commerce.order.dto.CreateOrderRequest;
import com.commerce.order.dto.OrderResponse;
import com.commerce.order.global.response.CommonResponse;
import com.commerce.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@Validated
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @Operation(
            summary = "주문 생성",
            description = "고객 ID와 주문 항목 목록으로 새 주문을 생성한다. 총액은 서버에서 항목 합으로 계산한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "주문 생성 성공",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (필수 값 누락, 유효하지 않은 요청 값)",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class)))
    })
    public CommonResponse<OrderResponse> createOrder(
            @RequestBody @Valid CreateOrderRequest request
    ) {
        return CommonResponse.success(orderService.createOrder(request));
    }

    @GetMapping("/{orderId}")
    @Operation(
            summary = "주문 단건 조회",
            description = "주문 ID로 주문 상세(항목 포함)를 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class))),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 주문",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class)))
    })
    public CommonResponse<OrderResponse> getOrder(
            @Parameter(description = "조회할 주문 ID", example = "1", required = true)
            @Positive @PathVariable Long orderId
    ) {
        return CommonResponse.success(orderService.getOrder(orderId));
    }
}