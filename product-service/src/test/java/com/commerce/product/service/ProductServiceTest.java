package com.commerce.product.service;

import com.commerce.product.domain.Product;
import com.commerce.product.dto.ProductResponse;
import com.commerce.product.dto.StockDeductResponse;
import com.commerce.product.exception.ProductErrorCase;
import com.commerce.product.fixture.ProductRequestFixture;
import com.commerce.product.global.exception.ApplicationException;
import com.commerce.product.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProductService 단위 테스트")
class ProductServiceTest {

    @InjectMocks
    private ProductService productService;

    @Mock
    private ProductRepository productRepository;

    @Nested
    @DisplayName("createProduct")
    class CreateProduct {

        @Test
        @DisplayName("성공 - 상품 저장 후 응답 반환")
        void success() {
            Product saved = mock(Product.class);
            given(saved.getId()).willReturn(1L);
            given(saved.getName()).willReturn("키보드");
            given(saved.getPrice()).willReturn(30000L);
            given(saved.getStockQuantity()).willReturn(10);
            given(productRepository.save(any(Product.class))).willReturn(saved);

            ProductResponse response = productService.createProduct(ProductRequestFixture.defaultCreateRequest());

            assertThat(response.getProductId()).isEqualTo(1L);
            assertThat(response.getStockQuantity()).isEqualTo(10);
            then(productRepository).should().save(any(Product.class));
        }
    }

    @Nested
    @DisplayName("getProduct")
    class GetProduct {

        @Test
        @DisplayName("성공 - 상품 단건 조회")
        void success() {
            Product product = mock(Product.class);
            given(product.getId()).willReturn(1L);
            given(product.getName()).willReturn("키보드");
            given(product.getPrice()).willReturn(30000L);
            given(product.getStockQuantity()).willReturn(10);
            given(productRepository.findById(1L)).willReturn(Optional.of(product));

            ProductResponse response = productService.getProduct(1L);

            assertThat(response.getName()).isEqualTo("키보드");
            assertThat(response.getPrice()).isEqualTo(30000L);
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 상품 → ApplicationException(PRODUCT_NOT_FOUND)")
        void notFound() {
            given(productRepository.findById(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> productService.getProduct(99L))
                    .isInstanceOf(ApplicationException.class)
                    .extracting(e -> ((ApplicationException) e).getErrorCase())
                    .isEqualTo(ProductErrorCase.PRODUCT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("getAllProducts")
    class GetAllProducts {

        @Test
        @DisplayName("성공 - 전체 상품 목록")
        void success() {
            Product p1 = mock(Product.class);
            given(p1.getId()).willReturn(1L);
            given(p1.getName()).willReturn("키보드");
            Product p2 = mock(Product.class);
            given(p2.getId()).willReturn(2L);
            given(p2.getName()).willReturn("마우스");
            given(productRepository.findAll()).willReturn(List.of(p1, p2));

            List<ProductResponse> result = productService.getAllProducts();

            assertThat(result).hasSize(2)
                    .extracting(ProductResponse::getName).containsExactly("키보드", "마우스");
        }
    }

    @Nested
    @DisplayName("deductStock")
    class DeductStock {

        @Test
        @DisplayName("성공 - 재고 차감 후 상품 이름/가격을 함께 반환")
        void success() {
            Product product = mock(Product.class);
            given(product.getId()).willReturn(1L);
            given(product.getName()).willReturn("키보드");
            given(product.getPrice()).willReturn(30000L);
            given(productRepository.findById(1L)).willReturn(Optional.of(product));
            given(productRepository.decreaseStock(1L, 2)).willReturn(1);

            StockDeductResponse response = productService.deductStock(ProductRequestFixture.deductRequest(1L, 2));

            assertThat(response.getItems()).hasSize(1);
            assertThat(response.getItems().get(0).getProductName()).isEqualTo("키보드");
            assertThat(response.getItems().get(0).getUnitPrice()).isEqualTo(30000L);
            then(productRepository).should().decreaseStock(1L, 2);
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 상품 → PRODUCT_NOT_FOUND, 차감 시도 안 함")
        void notFound() {
            given(productRepository.findById(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> productService.deductStock(ProductRequestFixture.deductRequest(99L, 1)))
                    .isInstanceOf(ApplicationException.class)
                    .extracting(e -> ((ApplicationException) e).getErrorCase())
                    .isEqualTo(ProductErrorCase.PRODUCT_NOT_FOUND);
            then(productRepository).should(never()).decreaseStock(anyLong(), anyInt());
        }

        @Test
        @DisplayName("실패 - 차감 0건(재고 부족) → OUT_OF_STOCK")
        void outOfStock() {
            Product product = mock(Product.class);
            given(product.getId()).willReturn(1L);
            given(productRepository.findById(1L)).willReturn(Optional.of(product));
            given(productRepository.decreaseStock(1L, 9999)).willReturn(0);

            assertThatThrownBy(() -> productService.deductStock(ProductRequestFixture.deductRequest(1L, 9999)))
                    .isInstanceOf(ApplicationException.class)
                    .extracting(e -> ((ApplicationException) e).getErrorCase())
                    .isEqualTo(ProductErrorCase.OUT_OF_STOCK);
        }
    }
}