package com.commerce.order.controller;

import com.commerce.order.exception.OrderErrorCase;
import com.commerce.order.fixture.OrderResponseFixture;
import com.commerce.order.global.exception.ApplicationException;
import com.commerce.order.service.OrderService;
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

@WebMvcTest(controllers = OrderController.class)
@DisplayName("OrderController 슬라이스 테스트")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    private static final String BASE = "/api/v1/orders";

    @Test
    @DisplayName("성공 - 주문 생성 → 200, CommonResponse.data 반환")
    void create_success() throws Exception {
        given(orderService.createOrder(any())).willReturn(OrderResponseFixture.defaultResponse());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId":1,"items":[{"productId":1,"quantity":2,"unitPrice":30000},{"productId":3,"quantity":1,"unitPrice":600000}]}
                        """))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(1))
                .andExpect(jsonPath("$.data.totalAmount").value(660000));
    }

    @Test
    @DisplayName("실패 - items 비어있음(@NotEmpty) → 400")
    void create_validation() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId":1,"items":[]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("실패 - product 서비스 장애 → 503, ORDER_004")
    void create_productUnavailable() throws Exception {
        given(orderService.createOrder(any()))
                .willThrow(ApplicationException.from(OrderErrorCase.PRODUCT_SERVICE_UNAVAILABLE));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId":1,"items":[{"productId":1,"quantity":1,"unitPrice":30000}]}
                        """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORDER_004"));
    }

    @Test
    @DisplayName("실패 - 재고 부족 번역 → 409, ORDER_003")
    void create_outOfStock() throws Exception {
        given(orderService.createOrder(any()))
                .willThrow(ApplicationException.from(OrderErrorCase.PRODUCT_OUT_OF_STOCK));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId":1,"items":[{"productId":1,"quantity":9999,"unitPrice":30000}]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_003"));
    }

    @Test
    @DisplayName("성공 - 주문 조회 → 200, data.orderId")
    void get_success() throws Exception {
        given(orderService.getOrder(1L)).willReturn(OrderResponseFixture.defaultResponse());

        mockMvc.perform(get(BASE + "/{orderId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(1));
    }

    @Test
    @DisplayName("실패 - 없는 주문 조회 → 404, ORDER_001")
    void get_notFound() throws Exception {
        given(orderService.getOrder(99L))
                .willThrow(ApplicationException.from(OrderErrorCase.ORDER_NOT_FOUND));

        mockMvc.perform(get(BASE + "/{orderId}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ORDER_001"));
    }
}