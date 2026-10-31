package com.inspien.eai.sender;

import com.inspien.eai.common.Order;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

// ORDER_TB 폴링: 접수(N) 주문을 읽고, 처리한 주문을 Y 로 표시한다 (JDBC Sender 의 폴링 쿼리 + 처리 표시)
// 다른 지원자와 같은 테이블을 쓰므로 모든 쿼리에 내 APPLICANT_KEY 조건을 건다. commit 하지 않는다
public class OrderDbPollingSender {

    public OrderDbPollingSender(String applicantKey) {
    }

    // STATUS = N 인 주문, ORDER_ID 순
    public List<Order> findReceived(Connection connection) throws SQLException {
        throw new UnsupportedOperationException("TODO");
    }

    // N → Y. 바뀐 행 수 (이미 Y 면 0)
    public int markShipped(Connection connection, String orderId) throws SQLException {
        throw new UnsupportedOperationException("TODO");
    }
}
