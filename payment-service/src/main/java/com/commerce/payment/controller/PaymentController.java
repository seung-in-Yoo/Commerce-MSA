package com.commerce.payment.controller;

import com.commerce.payment.dto.PaymentResponse;
import com.commerce.payment.global.response.CommonResponse;
import com.commerce.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 결제는 saga 이벤트로 생성되므로 POST는 없고, 관찰용 조회만
@RestController
@RequestMapping("/api/v1/payments")
@Validated
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping("/{paymentId}")
    @Operation(summary = "결제 단건 조회", description = "결제 ID로 주문ID·금액·상태를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class))),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 결제",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class)))
    })
    public CommonResponse<PaymentResponse> get(
            @Parameter(description = "조회할 결제 ID", example = "1", required = true)
            @Positive @PathVariable Long paymentId) {
        return CommonResponse.success(paymentService.getPayment(paymentId));
    }

    @GetMapping
    @Operation(summary = "결제 목록 조회", description = "모든 결제 내역을 조회한다. (saga 결과 확인용)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class)))
    })
    public CommonResponse<List<PaymentResponse>> getAll() {
        return CommonResponse.success(paymentService.getAllPayments());
    }
}