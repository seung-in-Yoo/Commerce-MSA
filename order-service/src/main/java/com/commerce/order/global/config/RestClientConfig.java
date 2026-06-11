package com.commerce.order.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// product-service를 동기 호출하기 위한 RestClient
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient productRestClient(@Value("${product.service.url}") String productServiceUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);   // 연결 수립 1초
        factory.setReadTimeout(2000);       // 응답 대기 2초

        return RestClient.builder()
                .baseUrl(productServiceUrl)
                .requestFactory(factory)
                .build();
    }
}