package com.inspien.eai.receiver;

import com.inspien.eai.mapping.Shipment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

// SHIPMENT_TB 저장. 다른 지원자와 같은 테이블을 쓰므로 모든 쿼리에 내 APPLICANT_KEY 조건을 건다. commit 하지 않는다
// ORDER_TB 와 같은 DB·계정이라 같은 연결로 주문 상태 변경과 한 트랜잭션으로 묶을 수 있다
public class ShipmentDbReceiver {

    private static final String FIND_MAX_ID_SQL =
            "SELECT MAX(SHIPMENT_ID) FROM SHIPMENT_TB WHERE APPLICANT_KEY = ?";
    private static final String INSERT_SQL =
            "INSERT INTO SHIPMENT_TB (SHIPMENT_ID, APPLICANT_KEY, ORDER_ID, ITEM_ID, ADDRESS) VALUES (?, ?, ?, ?, ?)";

    private final String applicantKey;

    public ShipmentDbReceiver(String applicantKey) {
        this.applicantKey = applicantKey;
    }

    // 없으면 null. ORDER_ID 와 같은 규칙(대문자 1자 + 숫자 3자리)이라 문자열 MAX 로 순서가 맞다
    public String findMaxShipmentId(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(FIND_MAX_ID_SQL)) {
            ps.setString(1, applicantKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); // MAX 는 행이 없어도 NULL 한 행을 돌려준다
                return rs.getString(1);
            }
        }
    }

    public void save(Connection connection, Shipment shipment) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
            ps.setString(1, shipment.shipmentId());
            ps.setString(2, applicantKey);
            ps.setString(3, shipment.orderId());
            ps.setString(4, shipment.itemId());
            ps.setString(5, shipment.address());
            ps.executeUpdate();
        }
    }
}
