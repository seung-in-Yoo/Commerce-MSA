package com.commerce.order.service;

import com.commerce.order.domain.Order;
import com.commerce.order.dto.CreateOrderRequest;
import com.commerce.order.dto.OrderResponse;
import com.commerce.order.exception.OrderErrorCase;
import com.commerce.order.global.client.ProductClient;
import com.commerce.order.global.client.dto.StockDeductApiRequest;
import com.commerce.order.global.client.dto.StockDeductApiResponse;
import com.commerce.order.global.exception.ApplicationException;
import com.commerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductClient productClient;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        // product-service에 "재고 차감 + 상품 정보"를 동기 HTTP로 요청
        // 남의 DB(상품/재고)는 JOIN 못 하니 소유 서비스에 물어봄 (ID + API로만 접근)
        // product가 죽었거나(연결 거부) 느리면(타임아웃) 여기서 예외발생
        StockDeductApiRequest deductRequest = new StockDeductApiRequest(
                request.items().stream()
                        .map(item -> new StockDeductApiRequest.Line(item.productId(), item.quantity()))
                        .toList());
        List<StockDeductApiResponse.Item> deducted = productClient.deductStock(deductRequest);

        // product가 돌려준 이름/가격을 스냅샷으로 주문을 구성 -> 주문 항목은 이후 상품 가격이 바뀌어도 불변
        Order order = Order.create(request.customerId());
        deducted.forEach(item ->
                order.addItem(item.productId(), item.productName(), item.unitPrice(), item.quantity()));

        // 주문 저장
        // 재고는 이미 product에서 깎였는데 해당 save가 실패하면 재고만 줄고 주문은 없는 불일치가 남음
        // order의 로컬 DB 트랜잭션은 위의 HTTP 호출을 롤백하지 못함 (분산 트랜잭션 문제)
        // HTTP 호출이 해당 트랜잭션 경계 안에 있어 네트워크 시간만큼 DB 커넥션을 잡음
        Order saved = orderRepository.save(order);
        return OrderResponse.from(saved);
    }

    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> ApplicationException.from(OrderErrorCase.ORDER_NOT_FOUND));
        return OrderResponse.from(order);
    }
}