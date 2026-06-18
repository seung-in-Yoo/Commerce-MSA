package com.commerce.gateway.fallback;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class FallbackController {

	@RequestMapping("/fallback/orders")
	public ResponseEntity<Map<String, Object>> orderFallback() {
		return serviceUnavailable("주문 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해주세요.");
	}

	@RequestMapping("/fallback/products")
	public ResponseEntity<Map<String, Object>> productFallback() {
		return serviceUnavailable("상품 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해주세요.");
	}

	@RequestMapping("/fallback/payments")
	public ResponseEntity<Map<String, Object>> paymentFallback() {
		return serviceUnavailable("결제 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해주세요.");
	}

	private ResponseEntity<Map<String, Object>> serviceUnavailable(String message) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(Map.of(
						"success", false,
						"code", "GATEWAY_CB_OPEN",
						"message", message));
	}
}