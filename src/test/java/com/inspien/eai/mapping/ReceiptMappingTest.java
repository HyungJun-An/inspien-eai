package com.inspien.eai.mapping;

import com.inspien.eai.common.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptMappingTest {

    private final ReceiptMapping mapping = new ReceiptMapping("안형준");

    @Test
    @DisplayName("영수증 파일명은 '이름_ORDER_ID.txt'다")
    void fileNameIsApplicantNameAndOrderId() {
        assertThat(mapping.fileName(order("A001"))).isEqualTo("안형준_A001.txt");
    }

    @Test
    @DisplayName("영수증 내용은 헤더 1줄 + 주문 1줄, '|'로 구분한 UTF-8 텍스트다")
    void contentIsHeaderAndOneDelimitedLine() {
        String content = new String(mapping.content(order("A001")), StandardCharsets.UTF_8);

        assertThat(content).isEqualTo("""
                ORDER_ID|USER_ID|NAME|ITEM_ID|ITEM_NAME|PRICE
                A001|USER01|김철수|ITEM45|선글라스|95000
                """);
    }

    @Test
    @DisplayName("주문마다 파일 1개를 주문 순서대로 만든다")
    void makesOneFilePerOrderInOrder() {
        Map<String, byte[]> files = mapping.toFiles(List.of(order("A002"), order("A001"), order("A003")));

        assertThat(files.keySet()).containsExactly("안형준_A002.txt", "안형준_A001.txt", "안형준_A003.txt");
    }

    private Order order(String orderId) {
        return new Order(orderId, "USER01", "ITEM45", "김철수", "서울특별시 강남구", "선글라스", "95000", Order.RECEIVED);
    }
}
