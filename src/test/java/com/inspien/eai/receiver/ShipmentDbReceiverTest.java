package com.inspien.eai.receiver;

import com.inspien.eai.OracleTestSupport;
import com.inspien.eai.common.IdGenerator;
import com.inspien.eai.mapping.Shipment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

// 과제 Oracle 에 직접 붙어서 SQL 을 확인한다. 모든 테스트는 끝나면 롤백된다 (DELETE 권한 없음)
@EnabledIf("com.inspien.eai.OracleTestSupport#hasLocalConfig")
class ShipmentDbReceiverTest {

    private final String applicantKey = OracleTestSupport.applicantKey();
    private final ShipmentDbReceiver receiver = new ShipmentDbReceiver(applicantKey);

    private Connection connection;
    // 이미 DB 에 있는 내 운송 데이터 다음 번호부터 쓴다
    private String firstId;
    private String secondId;

    @BeforeEach
    void setUp() throws Exception {
        connection = OracleTestSupport.openRollbackOnly();
        firstId = IdGenerator.next(receiver.findMaxShipmentId(connection));
        secondId = IdGenerator.next(firstId);
    }

    @AfterEach
    void rollback() throws Exception {
        connection.rollback();
        connection.close();
    }

    @Test
    @DisplayName("ORDER_ID, ITEM_ID, 주소를 SHIPMENT_TB에 저장한다")
    void savesShipment() throws Exception {
        receiver.save(connection, new Shipment(firstId, "A001", "ITEM45", "서울특별시 강남구"));

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM SHIPMENT_TB WHERE SHIPMENT_ID = ? AND APPLICANT_KEY = ?")) {
            ps.setString(1, firstId);
            ps.setString(2, applicantKey);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("ORDER_ID")).isEqualTo("A001");
                assertThat(rs.getString("ITEM_ID")).isEqualTo("ITEM45");
                assertThat(rs.getString("ADDRESS")).isEqualTo("서울특별시 강남구");
            }
        }
    }

    @Test
    @DisplayName("내 APPLICANT_KEY 안에서 가장 큰 SHIPMENT_ID를 찾는다")
    void findsLargestShipmentIdOfMine() throws Exception {
        receiver.save(connection, new Shipment(firstId, "A001", "ITEM45", "서울특별시 강남구"));
        receiver.save(connection, new Shipment(secondId, "A002", "ITEM46", "서울특별시 강남구"));

        assertThat(receiver.findMaxShipmentId(connection)).isEqualTo(secondId);
    }
}
