package com.inspien.eai.mapping;

import com.inspien.eai.common.Order;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 주문 → 회계팀 영수증 파일 ('|' 구분 txt, 주문 1건 = 파일 1개)
// XML 을 회계팀이 바로 열어 볼 수 있는 구분자 파일로 바꾼다
public class ReceiptMapping {

    private static final String HEADER = "ORDER_ID|USER_ID|NAME|ITEM_ID|ITEM_NAME|PRICE";

    private final String applicantName;

    public ReceiptMapping(String applicantName) {
        this.applicantName = applicantName;
    }

    // 이름_ORDER_ID.txt : 과제 규칙상 이름(한글) 필수, ORDER_ID 가 겹치지 않으니 파일명도 겹치지 않는다
    public String fileName(Order order) {
        return applicantName + "_" + order.getOrderId() + ".txt";
    }

    // 헤더 1줄 + 주문 1줄 (UTF-8). 값에 '|'·줄바꿈이 없다는 건 OrderXmlMapping 에서 이미 확인했다
    public byte[] content(Order order) {
        String line = String.join("|",
                order.getOrderId(), order.getUserId(), order.getName(),
                order.getItemId(), order.getItemName(), order.getPrice());
        return (HEADER + "\n" + line + "\n").getBytes(StandardCharsets.UTF_8);
    }

    // 파일명 → 내용. 주문 순서대로 올라가도록 LinkedHashMap
    public Map<String, byte[]> toFiles(List<Order> orders) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Order order : orders) {
            files.put(fileName(order), content(order));
        }
        return files;
    }
}
