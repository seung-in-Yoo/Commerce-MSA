// 실행: k6 run loadtest/scripts/order-load.js
// 게이트웨이: k6 run -e ORDER_URL=http://localhost:8000 loadtest/scripts/order-load.js


import http from 'k6/http';
import { check } from 'k6';

const ORDER_URL = __ENV.ORDER_URL || 'http://localhost:8080';
const PRODUCT_URL = __ENV.PRODUCT_URL || 'http://localhost:8081';

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '90s';

export const options = {
  scenarios: {
    constant_orders: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    http_req_failed: ['rate<0.05'],
  },
};

export function setup() {
  const payload = JSON.stringify({
    name: 'load-test-product',
    price: 1000,
    stockQuantity: 100000000, // 부하 총량보다 훨씬 크게 잡아 재고 고갈을 방지
  });
  const res = http.post(`${PRODUCT_URL}/api/v1/products`, payload, JSON_HEADERS);
  check(res, { 'setup: product created (2xx)': (r) => r.status >= 200 && r.status < 300 });

  const productId = res.json('data.productId');
  console.log(`[setup] productId=${productId}, stock=100,000,000`);
  return { productId };
}

// default: 각 도착마다 주문 1건 생성
export default function (data) {
  const payload = JSON.stringify({
    customerId: 1,
    items: [{ productId: data.productId, quantity: 1, unitPrice: 1000 }],
  });
  const res = http.post(`${ORDER_URL}/api/v1/orders`, payload, JSON_HEADERS);
  check(res, { 'order accepted (2xx)': (r) => r.status >= 200 && r.status < 300 });
}