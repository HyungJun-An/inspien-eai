package com.inspien.eai.receiver;

import com.inspien.eai.OracleTestSupport;
import com.inspien.eai.common.IdGenerator;
import com.inspien.eai.common.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 과제 Oracle 에 직접 붙어서 SQL 을 확인한다. 모든 테스트는 끝나면 롤백된다 (DELETE 권한 없음)
@EnabledIf("com.inspien.eai.OracleTestSupport#hasLocalConfig")
class OrderDbReceiverTest {

    private final String applicantKey = OracleTestSupport.applicantKey();
    private final OrderDbReceiver receiver = new OrderDbReceiver(applicantKey);

    private Connection connection;
    // 이미 DB 에 있는 내 주문 다음 번호부터 쓴다
    private String firstId;
    private String secondId;

    @BeforeEach
    void setUp() throws Exception {
        connection = OracleTestSupport.openRollbackOnly();
        firstId = IdGenerator.next(receiver.findMaxOrderId(connection));
        secondId = IdGenerator.next(firstId);
    }

    @AfterEach
    void rollback() throws Exception {
        connection.rollback();
        connection.close();
    }

    @Test
    @DisplayName("한글을 포함한 모든 컬럼이 ORDER_TB에 저장된다")
    void savesAllFieldsIncludingKorean() throws Exception {
        receiver.saveAll(connection, List.of(order(firstId, "ITEM45", "선글라스", "95000")));

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM ORDER_TB WHERE ORDER_ID = ? AND APPLICANT_KEY = ?")) {
            ps.setString(1, firstId);
            ps.setString(2, applicantKey);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("USER_ID")).isEqualTo("USER01");
                assertThat(rs.getString("ITEM_ID")).isEqualTo("ITEM45");
                assertThat(rs.getString("NAME")).isEqualTo("김철수");
                assertThat(rs.getString("ADDRESS")).isEqualTo("서울특별시 강남구");
                assertThat(rs.getString("ITEM_NAME")).isEqualTo("선글라스");
                assertThat(rs.getString("PRICE")).isEqualTo("95000");
                assertThat(rs.getString("STATUS")).isEqualTo("N");
            }
        }
    }

    @Test
    @DisplayName("여러 건을 한 번에 저장하고, 내 APPLICANT_KEY 안에서 가장 큰 ORDER_ID를 찾는다")
    void savesBatchAndFindsLargestOfMine() throws Exception {
        receiver.saveAll(connection, List.of(
                order(firstId, "ITEM45", "선글라스", "95000"),
                order(secondId, "ITEM46", "안경테", "45000")));

        assertThat(receiver.findMaxOrderId(connection)).isEqualTo(secondId);
    }

    private Order order(String orderId, String itemId, String itemName, String price) {
        return new Order(orderId, "USER01", itemId, "김철수", "서울특별시 강남구", itemName, price, Order.RECEIVED);
    }
}
