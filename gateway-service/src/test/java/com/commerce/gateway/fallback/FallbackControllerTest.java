package com.commerce.gateway.fallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FallbackController 단위 테스트")
class FallbackControllerTest {

	private final FallbackController controller = new FallbackController();

	@Test
	@DisplayName("성공 - 상품 폴백은 503 + success=false 본문을 반환")
	void productFallback_returns503() {
		assertServiceUnavailable(controller.productFallback());
	}

	@Test
	@DisplayName("성공 - 주문 폴백은 503 + success=false 본문을 반환")
	void orderFallback_returns503() {
		assertServiceUnavailable(controller.orderFallback());
	}

	@Test
	@DisplayName("성공 - 결제 폴백은 503 + success=false 본문을 반환")
	void paymentFallback_returns503() {
		assertServiceUnavailable(controller.paymentFallback());
	}

	private void assertServiceUnavailable(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody())
				.containsEntry("success", false)
				.containsEntry("code", "GATEWAY_CB_OPEN");
	}
}