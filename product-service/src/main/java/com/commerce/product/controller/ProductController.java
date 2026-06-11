package com.commerce.product.controller;

import com.commerce.product.dto.ProductCreateRequest;
import com.commerce.product.dto.ProductResponse;
import com.commerce.product.dto.StockDeductRequest;
import com.commerce.product.dto.StockDeductResponse;
import com.commerce.product.global.response.CommonResponse;
import com.commerce.product.service.ProductService;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@Validated
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    @Operation(summary = "상품 등록", description = "이름·가격·초기 재고로 상품을 등록한다. (주문 테스트용 시드 데이터)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "등록 성공",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class)))
    })
    public CommonResponse<ProductResponse> create(@RequestBody @Valid ProductCreateRequest request) {
        return CommonResponse.success(productService.createProduct(request));
    }

    @GetMapping("/{productId}")
    @Operation(summary = "상품 단건 조회", description = "상품 ID로 이름·가격·재고를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class))),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 상품",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class)))
    })
    public CommonResponse<ProductResponse> get(
            @Parameter(description = "조회할 상품 ID", example = "1", required = true)
            @Positive @PathVariable Long productId) {
        return CommonResponse.success(productService.getProduct(productId));
    }

    @GetMapping
    @Operation(summary = "상품 목록 조회", description = "등록된 모든 상품을 조회한다. (재고 확인용)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class)))
    })
    public CommonResponse<List<ProductResponse>> getAll() {
        return CommonResponse.success(productService.getAllProducts());
    }

    @PostMapping("/stock/deduct")
    @Operation(summary = "재고 차감 (서버 간 호출)",
            description = "order-service가 주문 생성 시 호출한다. 여러 상품을 한 번에 차감하며 전부 성공 또는 전부 롤백된다. "
                    + "차감된 상품의 이름·가격을 함께 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "차감 성공",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class))),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 상품",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class))),
            @ApiResponse(responseCode = "409", description = "재고 부족",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class)))
    })
    public CommonResponse<StockDeductResponse> deductStock(@RequestBody @Valid StockDeductRequest request) {
        return CommonResponse.success(productService.deductStock(request));
    }
}