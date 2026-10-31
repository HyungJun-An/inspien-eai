package com.inspien.eai.sender;

import com.inspien.eai.OracleTestSupport;
import com.inspien.eai.common.IdGenerator;
import com.inspien.eai.common.Order;
import com.inspien.eai.receiver.OrderDbReceiver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 과제 Oracle 에 직접 붙어서 SQL 을 확인한다. 모든 테스트는 끝나면 롤백된다 (DELETE 권한 없음)
@EnabledIf("com.inspien.eai.OracleTestSupport#hasLocalConfig")
class OrderDbPollingSenderTest {

    private final String applicantKey = OracleTestSupport.applicantKey();
    private final OrderDbPollingSender polling = new OrderDbPollingSender(applicantKey);
    private final OrderDbReceiver orderDb = new OrderDbReceiver(applicantKey);

    private Connection connection;
    private String firstId;
    private String secondId;

    @BeforeEach
    void setUp() throws Exception {
        connection = OracleTestSupport.openRollbackOnly();
        firstId = IdGenerator.next(orderDb.findMaxOrderId(connection));
        secondId = IdGenerator.next(firstId);
    }

    @AfterEach
    void rollback() throws Exception {
        connection.rollback();
        connection.close();
    }

    @Test
    @DisplayName("접수(N) 주문만 ORDER_ID 순으로 읽는다")
    void findsOnlyReceivedOrdersInIdOrder() throws Exception {
        orderDb.saveAll(connection, List.of(order(secondId, "ITEM46"), order(firstId, "ITEM45")));
        polling.markShipped(connection, firstId);

        List<Order> received = polling.findReceived(connection);

        assertThat(received).extracting(Order::getOrderId).contains(secondId).doesNotContain(firstId);
        Order second = received.stream().filter(o -> o.getOrderId().equals(secondId)).findFirst().orElseThrow();
        assertThat(second.getItemId()).isEqualTo("ITEM46");
        assertThat(second.getAddress()).isEqualTo("서울특별시 강남구");
        assertThat(second.getStatus()).isEqualTo(Order.RECEIVED);
    }

    @Test
    @DisplayName("N일 때만 Y로 바뀐다 (두 번째 호출은 0건)")
    void marksShippedOnlyOnce() throws Exception {
        orderDb.saveAll(connection, List.of(order(firstId, "ITEM45")));

        // 배치가 두 번 돌아도 한 번만 바뀌어야 중복 배송이 안 생긴다
        assertThat(polling.markShipped(connection, firstId)).isEqualTo(1);
        assertThat(polling.markShipped(connection, firstId)).isEqualTo(0);
    }

    private Order order(String orderId, String itemId) {
        return new Order(orderId, "USER01", itemId, "김철수", "서울특별시 강남구", "선글라스", "95000", Order.RECEIVED);
    }
}
