package com.inspien.eai.receiver;

import com.inspien.eai.common.Order;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

// ORDER_TB 저장. 다른 지원자와 같은 테이블을 쓰므로 모든 쿼리에 내 APPLICANT_KEY 조건을 건다.
// commit 하지 않는다 (트랜잭션은 받은 Connection 의 주인인 flow 가 정한다)
public class OrderDbReceiver {

    private static final String FIND_MAX_ID_SQL =
            "SELECT MAX(ORDER_ID) FROM ORDER_TB WHERE APPLICANT_KEY = ?";
    private static final String INSERT_SQL =
            "INSERT INTO ORDER_TB (ORDER_ID, APPLICANT_KEY, USER_ID, ITEM_ID, NAME, ADDRESS, ITEM_NAME, PRICE, STATUS) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final String applicantKey;

    public OrderDbReceiver(String applicantKey) {
        this.applicantKey = applicantKey;
    }

    // 없으면 null. ID 가 '대문자 1자 + 숫자 3자리' 고정 길이라 문자열 MAX 로도 순서가 맞다 (A999 < B001)
    public String findMaxOrderId(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(FIND_MAX_ID_SQL)) {
            ps.setString(1, applicantKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); // MAX 는 행이 없어도 NULL 한 행을 돌려준다
                return rs.getString(1);
            }
        }
    }

    // 한 건씩 보내지 않고 묶어서(batch) 보낸다 → 63건이어도 DB 왕복 1번
    public void saveAll(Connection connection, List<Order> orders) throws SQLException {
        if (orders.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
            for (Order order : orders) {
                ps.setString(1, order.getOrderId());
                ps.setString(2, applicantKey);
                ps.setString(3, order.getUserId());
                ps.setString(4, order.getItemId());
                ps.setString(5, order.getName());
                ps.setString(6, order.getAddress());
                ps.setString(7, order.getItemName());
                ps.setString(8, order.getPrice());
                ps.setString(9, order.getStatus());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
