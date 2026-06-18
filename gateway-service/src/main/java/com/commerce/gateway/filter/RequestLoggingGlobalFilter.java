package com.commerce.gateway.filter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// 모든 요청을 게이트웨이 한 곳에서 한 줄로 남기는 글로벌 필터 -> 로깅을 서비스마다 반복하지 않고 게이트웨이에서 한 번만 처리
@Component
public class RequestLoggingGlobalFilter implements GlobalFilter, Ordered {

	private static final Logger log = LoggerFactory.getLogger(RequestLoggingGlobalFilter.class);

	private final Tracer tracer;

	public RequestLoggingGlobalFilter(Tracer tracer) {
		this.tracer = tracer;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		long startMillis = System.currentTimeMillis();
		ServerHttpRequest request = exchange.getRequest();
		String method = request.getMethod().name();
		String path = request.getURI().getRawPath();

		return chain.filter(exchange).then(Mono.fromRunnable(() -> {
			Span currentSpan = tracer.currentSpan();
			String traceId = (currentSpan != null) ? currentSpan.context().traceId() : "no-trace";
			Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
			String routeId = (route != null) ? route.getId() : "no-route";
			long tookMillis = System.currentTimeMillis() - startMillis;
			log.info("[gateway] traceId={} {} {} -> route={} status={} ({}ms)",
					traceId, method, path, routeId, exchange.getResponse().getStatusCode(), tookMillis);
		}));
	}

	@Override
	public int getOrder() {
		// 게이트웨이 전체 처리 시간 측정
		return Ordered.HIGHEST_PRECEDENCE;
	}
}