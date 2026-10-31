package com.inspien.eai.mapping;

// SHIPMENT_TB 1행: 운송회사에 필요한 것만 (어떤 주문의 어떤 상품을 어디로)
public record Shipment(String shipmentId, String orderId, String itemId, String address) {
}
