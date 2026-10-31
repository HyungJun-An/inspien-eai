package com.inspien.eai.mapping;

// 주문 XML(EUC-KR, 루트 없음) → 주문 목록
public class OrderXmlMapping {

    // HEADER 와 ITEM 을 USER_ID 로 합친다. HEADER 가 없는 ITEM 은 로그만 남기고 건너뛴다.
    // 데이터가 잘못됐으면 InvalidOrderException
    public ParsedOrders parse(byte[] xml) {
        throw new UnsupportedOperationException("TODO");
    }
}
