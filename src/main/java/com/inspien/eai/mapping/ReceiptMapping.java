package com.inspien.eai.mapping;

import com.inspien.eai.common.Order;

import java.util.List;
import java.util.Map;

// 주문 → 회계팀 영수증 파일 ('|' 구분 txt, 주문 1건 = 파일 1개)
public class ReceiptMapping {

    public ReceiptMapping(String applicantName) {
    }

    // 이름_ORDER_ID.txt
    public String fileName(Order order) {
        throw new UnsupportedOperationException("TODO");
    }

    // 헤더 1줄 + 주문 1줄 (UTF-8)
    public byte[] content(Order order) {
        throw new UnsupportedOperationException("TODO");
    }

    // 파일명 → 내용, 주문 순서 유지
    public Map<String, byte[]> toFiles(List<Order> orders) {
        throw new UnsupportedOperationException("TODO");
    }
}
