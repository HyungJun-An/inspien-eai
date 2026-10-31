package com.inspien.eai.receiver;

import com.inspien.eai.common.Order;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

// ORDER_TB 저장. 다른 지원자와 같은 테이블을 쓰므로 모든 쿼리에 내 APPLICANT_KEY 조건을 건다.
// commit 하지 않는다 (트랜잭션은 받은 Connection 의 주인인 flow 가 정한다)
public class OrderDbReceiver {

    public OrderDbReceiver(String applicantKey) {
    }

    // 없으면 null
    public String findMaxOrderId(Connection connection) throws SQLException {
        throw new UnsupportedOperationException("TODO");
    }

    // 한 건씩 보내지 않고 묶어서(batch) 보낸다
    public void saveAll(Connection connection, List<Order> orders) throws SQLException {
        throw new UnsupportedOperationException("TODO");
    }
}
