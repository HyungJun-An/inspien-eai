package com.inspien.eai.mapping;

import com.inspien.eai.common.Order;

import java.util.List;

// 매핑 결과: 저장할 주문 + 제외한 항목("USER16/ITEM64" 형식) + ITEM 이 하나도 없는 HEADER 의 USER_ID
public record ParsedOrders(List<Order> orders, List<String> skipped, List<String> headersWithoutItem) {
}
