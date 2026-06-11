package com.commerce.product.service;

import com.commerce.product.domain.Product;
import com.commerce.product.dto.ProductCreateRequest;
import com.commerce.product.dto.ProductResponse;
import com.commerce.product.dto.StockDeductRequest;
import com.commerce.product.dto.StockDeductResponse;
import com.commerce.product.exception.ProductErrorCase;
import com.commerce.product.global.exception.ApplicationException;
import com.commerce.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public ProductResponse createProduct(ProductCreateRequest request) {
        Product product = productRepository.save(
                Product.create(request.name(), request.price(), request.stockQuantity()));
        return ProductResponse.from(product);
    }

    public ProductResponse getProduct(Long productId) {
        return ProductResponse.from(findProduct(productId));
    }

    public List<ProductResponse> getAllProducts() {
        return productRepository.findAll().stream()
                .map(ProductResponse::from)
                .toList();
    }

    // 재고 차감. order-service가 주문 생성 시 호출 (여러 항목이 전부 차감 or 전부 롤백으로 묶임)
    // 차감된 상품의 이름/가격을 함께 반환해 order가 스냅샷으로 저장하게 함
    @Transactional
    public StockDeductResponse deductStock(StockDeductRequest request) {
        List<StockDeductResponse.Item> items = new ArrayList<>();
        for (StockDeductRequest.Line line : request.items()) {
            Product product = findProduct(line.productId());            // 존재 검증 + 이름/가격 확보
            int updated = productRepository.decreaseStock(line.productId(), line.quantity());
            if (updated == 0) {
                // 상품은 존재하는데 차감 실패 → 재고 부족
                throw ApplicationException.from(ProductErrorCase.OUT_OF_STOCK);
            }
            items.add(StockDeductResponse.Item.builder()
                    .productId(product.getId())
                    .productName(product.getName())
                    .unitPrice(product.getPrice())
                    .quantity(line.quantity())
                    .build());
        }
        return StockDeductResponse.builder().items(items).build();
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> ApplicationException.from(ProductErrorCase.PRODUCT_NOT_FOUND));
    }
}