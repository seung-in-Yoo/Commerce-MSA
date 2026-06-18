package com.commerce.gateway.filter;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequestLoggingGlobalFilter 단위 테스트")
class RequestLoggingGlobalFilterTest {

	private final RequestLoggingGlobalFilter filter = new RequestLoggingGlobalFilter(Tracer.NOOP);

	@Nested
	@DisplayName("filter")
	class Filter {

		@Test
		@DisplayName("성공 - 체인을 통과시키고 정상 완료")
		void passes_through_chain() {
			MockServerWebExchange exchange = MockServerWebExchange.from(
					MockServerHttpRequest.get("/api/v1/products/1"));
			AtomicBoolean chainCalled = new AtomicBoolean(false);
			GatewayFilterChain chain = ex -> {
				chainCalled.set(true);
				return Mono.empty();
			};

			StepVerifier.create(filter.filter(exchange, chain))
					.verifyComplete();

			assertThat(chainCalled).isTrue();
		}
	}

	@Nested
	@DisplayName("getOrder")
	class GetOrder {

		@Test
		@DisplayName("성공 - 가장 먼저 진입하도록 HIGHEST_PRECEDENCE를 반환")
		void returns_highest_precedence() {
			assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE);
		}
	}
}