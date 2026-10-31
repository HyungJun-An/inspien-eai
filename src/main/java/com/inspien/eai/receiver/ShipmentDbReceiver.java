package com.inspien.eai.receiver;

import com.inspien.eai.mapping.Shipment;

import java.sql.Connection;
import java.sql.SQLException;

// SHIPMENT_TB 저장. 다른 지원자와 같은 테이블을 쓰므로 모든 쿼리에 내 APPLICANT_KEY 조건을 건다. commit 하지 않는다
public class ShipmentDbReceiver {

    public ShipmentDbReceiver(String applicantKey) {
    }

    // 없으면 null
    public String findMaxShipmentId(Connection connection) throws SQLException {
        throw new UnsupportedOperationException("TODO");
    }

    public void save(Connection connection, Shipment shipment) throws SQLException {
        throw new UnsupportedOperationException("TODO");
    }
}
