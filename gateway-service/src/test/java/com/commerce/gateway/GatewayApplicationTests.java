package com.commerce.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@DisplayName("Gateway 컨텍스트 로드 테스트")
class GatewayApplicationTests {

	@Test
	@DisplayName("성공 - 라우트 설정과 함께 애플리케이션 컨텍스트가 로드된다")
	void contextLoads() {
	}
}