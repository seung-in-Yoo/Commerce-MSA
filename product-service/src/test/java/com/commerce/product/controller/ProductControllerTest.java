package com.commerce.product.controller;

import com.commerce.product.exception.ProductErrorCase;
import com.commerce.product.fixture.ProductResponseFixture;
import com.commerce.product.global.exception.ApplicationException;
import com.commerce.product.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProductController.class)
@DisplayName("ProductController 슬라이스 테스트")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    private static final String BASE = "/api/v1/products";

    @Test
    @DisplayName("성공 - 상품 등록 → 200, data.productId")
    void create_success() throws Exception {
        given(productService.createProduct(any())).willReturn(ProductResponseFixture.defaultResponse());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"키보드","price":30000,"stockQuantity":10}
                        """))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.productId").value(1));
    }

    @Test
    @DisplayName("실패 - 이름 누락(@NotBlank) → 400")
    void create_validation() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"","price":30000,"stockQuantity":10}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("성공 - 상품 단건 조회")
    void get_success() throws Exception {
        given(productService.getProduct(1L)).willReturn(ProductResponseFixture.defaultResponse());

        mockMvc.perform(get(BASE + "/{productId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("키보드"));
    }

    @Test
    @DisplayName("실패 - 없는 상품 조회 → 404, PRODUCT_001")
    void get_notFound() throws Exception {
        given(productService.getProduct(99L))
                .willThrow(ApplicationException.from(ProductErrorCase.PRODUCT_NOT_FOUND));

        mockMvc.perform(get(BASE + "/{productId}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("PRODUCT_001"));
    }

    @Test
    @DisplayName("성공 - 재고 차감 → 200, data.items[0].productName")
    void deduct_success() throws Exception {
        given(productService.deductStock(any())).willReturn(ProductResponseFixture.defaultDeductResponse());

        mockMvc.perform(post(BASE + "/stock/deduct").contentType(MediaType.APPLICATION_JSON).content("""
                        {"items":[{"productId":1,"quantity":2}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].productName").value("키보드"));
    }

    @Test
    @DisplayName("실패 - 재고 부족 → 409, PRODUCT_002")
    void deduct_outOfStock() throws Exception {
        given(productService.deductStock(any()))
                .willThrow(ApplicationException.from(ProductErrorCase.OUT_OF_STOCK));

        mockMvc.perform(post(BASE + "/stock/deduct").contentType(MediaType.APPLICATION_JSON).content("""
                        {"items":[{"productId":1,"quantity":9999}]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_002"));
    }
}