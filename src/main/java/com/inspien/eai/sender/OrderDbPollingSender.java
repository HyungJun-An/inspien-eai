package com.inspien.eai.sender;

import com.inspien.eai.common.Order;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// ORDER_TB 폴링: 접수(N) 주문을 읽고, 처리한 주문을 Y 로 표시한다 (JDBC Sender 의 폴링 쿼리 + 처리 표시)
// 다른 지원자와 같은 테이블을 쓰므로 모든 쿼리에 내 APPLICANT_KEY 조건을 건다. commit 하지 않는다
public class OrderDbPollingSender {

    private static final String FIND_SQL =
            "SELECT ORDER_ID, USER_ID, ITEM_ID, NAME, ADDRESS, ITEM_NAME, PRICE, STATUS FROM ORDER_TB "
                    + "WHERE APPLICANT_KEY = ? AND STATUS = ? ORDER BY ORDER_ID";
    private static final String MARK_SQL =
            "UPDATE ORDER_TB SET STATUS = ? WHERE ORDER_ID = ? AND APPLICANT_KEY = ? AND STATUS = ?";

    private final String applicantKey;

    public OrderDbPollingSender(String applicantKey) {
        this.applicantKey = applicantKey;
    }

    // STATUS = N 인 주문, ORDER_ID 순
    public List<Order> findReceived(Connection connection) throws SQLException {
        List<Order> orders = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(FIND_SQL)) {
            ps.setString(1, applicantKey);
            ps.setString(2, Order.RECEIVED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    orders.add(new Order(
                            rs.getString("ORDER_ID"), rs.getString("USER_ID"), rs.getString("ITEM_ID"),
                            rs.getString("NAME"), rs.getString("ADDRESS"), rs.getString("ITEM_NAME"),
                            rs.getString("PRICE"), rs.getString("STATUS")));
                }
            }
        }
        return orders;
    }

    // N → Y. 바뀐 행 수. 현재 상태가 N 일 때만 바꾼다 → 배치가 두 번 돌아도 한 번만 처리된다 (이미 Y 면 0)
    public int markShipped(Connection connection, String orderId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(MARK_SQL)) {
            ps.setString(1, Order.SHIPPED);
            ps.setString(2, orderId);
            ps.setString(3, applicantKey);
            ps.setString(4, Order.RECEIVED);
            return ps.executeUpdate();
        }
    }
}
