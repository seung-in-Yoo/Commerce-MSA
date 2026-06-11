package com.commerce.order.global.client;

import com.commerce.order.exception.OrderErrorCase;
import com.commerce.order.global.client.dto.ApiResult;
import com.commerce.order.global.client.dto.StockDeductApiRequest;
import com.commerce.order.global.client.dto.StockDeductApiResponse;
import com.commerce.order.global.exception.ApplicationException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;

// product-service 호출 래퍼 -> 외부 서비스의 응답/장애를 order 도메인의 에러로 번역
@Component
public class ProductClient {

    private final RestClient productRestClient;

    public ProductClient(RestClient productRestClient) {
        this.productRestClient = productRestClient;
    }

    public List<StockDeductApiResponse.Item> deductStock(StockDeductApiRequest request) {
        try {
            ApiResult<StockDeductApiResponse> result = productRestClient.post()
                    .uri("/api/v1/products/stock/deduct")
                    .body(request)
                    .retrieve()
                    // product가 에러 상태로 응답하면 order 도메인 에러로 번역함
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        int status = res.getStatusCode().value();
                        if (status == 404) {
                            throw ApplicationException.from(OrderErrorCase.PRODUCT_NOT_FOUND);
                        }
                        if (status == 409) {
                            throw ApplicationException.from(OrderErrorCase.PRODUCT_OUT_OF_STOCK);
                        }
                        // 그 외(5xx 등)는 의존 서비스 장애로 취급
                        throw ApplicationException.from(OrderErrorCase.PRODUCT_SERVICE_UNAVAILABLE);
                    })
                    .body(new ParameterizedTypeReference<>() {
                    });

            if (result == null || result.data() == null) {
                throw ApplicationException.from(OrderErrorCase.PRODUCT_SERVICE_UNAVAILABLE);
            }
            return result.data().items();

        } catch (ResourceAccessException e) {
            // 연결 거부(상대가 죽음) 또는 타임아웃(상대가 느림) → 실패 전파
            throw ApplicationException.from(OrderErrorCase.PRODUCT_SERVICE_UNAVAILABLE);
        }
    }
}