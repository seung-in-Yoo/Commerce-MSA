package com.commerce.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

@SpringBootApplication
public class GatewayApplication {

	public static void main(String[] args) {
		// reactive 체인 전반에 trace context(및 MDC)를 자동 복원 -> 로깅 필터에서 traceId를 읽을 수 있게 함
		Hooks.enableAutomaticContextPropagation();
		SpringApplication.run(GatewayApplication.class, args);
	}
}