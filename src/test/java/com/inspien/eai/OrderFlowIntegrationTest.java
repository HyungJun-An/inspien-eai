package com.inspien.eai;

import com.inspien.eai.common.Database;
import com.inspien.eai.flow.OrderReceiveFlow;
import com.inspien.eai.flow.ShipmentFlow;
import com.inspien.eai.flow.ShipmentResult;
import com.inspien.eai.mapping.OrderXmlMapping;
import com.inspien.eai.mapping.ReceiptMapping;
import com.inspien.eai.mapping.ShipmentMapping;
import com.inspien.eai.receiver.FtpReceiver;
import com.inspien.eai.receiver.OrderDbReceiver;
import com.inspien.eai.receiver.ShipmentDbReceiver;
import com.inspien.eai.sender.OrderDbPollingSender;
import com.inspien.eai.sender.OrderHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// 시연 리허설: 과제 SAMPLE_DATA 를 HTTP 로 보내 → 실제 Oracle ORDER_TB → 영수증 → 운송 연계 → SHIPMENT_TB 까지 한 번에
// 실제 DB 에는 아무것도 남기지 않는다: 모든 흐름이 커밋하지 않는 연결 하나를 같이 쓰고, 끝나면 rollback 한다
// 실제 FTP 에도 올리지 않는다 (업로드 꺼짐 → receipts/pending 에 남은 파일로 확인)
@EnabledIf("com.inspien.eai.OracleTestSupport#hasLocalConfig")
class OrderFlowIntegrationTest {

    private final String applicantKey = OracleTestSupport.applicantKey();
    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir
    Path receiptDir;

    private Connection real;
    private OrderHttpServer server;
    private OrderReceiveFlow orderReceiveFlow;
    private ShipmentFlow shipmentFlow;
    private int ordersBefore;
    private int shipmentsBefore;

    @BeforeEach
    void setUp() throws Exception {
        real = OracleTestSupport.openRollbackOnly();
        ordersBefore = count("SELECT COUNT(*) FROM ORDER_TB WHERE APPLICANT_KEY = ?");
        shipmentsBefore = count("SELECT COUNT(*) FROM SHIPMENT_TB WHERE APPLICANT_KEY = ?");

        // 앱과 똑같이 조립하되, DB 연결만 "커밋하지 않는 연결"로 바꾼다
        Database database = new Database(() -> neverCommits(real));
        FtpReceiver ftp = new FtpReceiver(OracleTestSupport.connectionInfo(), false, receiptDir);
        orderReceiveFlow = new OrderReceiveFlow(new OrderXmlMapping(), database,
                new OrderDbReceiver(applicantKey), new ReceiptMapping("안형준"), ftp);
        shipmentFlow = new ShipmentFlow(database, new OrderDbPollingSender(applicantKey),
                new ShipmentMapping(), new ShipmentDbReceiver(applicantKey));
        server = new OrderHttpServer(orderReceiveFlow);
        server.start(0);
    }

    @AfterEach
    void rollback() throws Exception {
        server.stop();
        real.rollback();
        real.close();
        // 정말 아무것도 안 남았는지 새 연결로 확인한다
        real = OracleTestSupport.openRollbackOnly();
        try {
            assertThat(count("SELECT COUNT(*) FROM ORDER_TB WHERE APPLICANT_KEY = ?")).isEqualTo(ordersBefore);
            assertThat(count("SELECT COUNT(*) FROM SHIPMENT_TB WHERE APPLICANT_KEY = ?")).isEqualTo(shipmentsBefore);
        } finally {
            real.rollback();
            real.close();
        }
    }

    @Test
    @DisplayName("SAMPLE_DATA: 63건 저장·7건 제외 → 영수증 63개 → 운송 연계 63건, 주문 상태 전부 Y")
    void sampleDataFlowsFromOrderToShipment() throws Exception {
        // 1. 쇼핑몰이 주문 XML 을 보낸다
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/orders"))
                .header("Content-Type", "application/xml; charset=EUC-KR")
                .POST(HttpRequest.BodyPublishers.ofByteArray(sampleData()))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JsonMapper.builder().build().readTree(response.body());
        assertThat(body.get("savedOrderIds").size()).isEqualTo(63);
        assertThat(body.get("skipped").size()).isEqualTo(7);

        // 2. ORDER_TB 에 접수(N) 63건, 영수증 63개 (업로드 꺼짐 → pending)
        assertThat(count("SELECT COUNT(*) FROM ORDER_TB WHERE APPLICANT_KEY = ? AND STATUS = 'N'")).isEqualTo(63);
        try (Stream<Path> files = Files.list(receiptDir.resolve("pending"))) {
            assertThat(files.count()).isEqualTo(63);
        }
        String firstId = body.get("savedOrderIds").get(0).asString();
        assertThat(Files.readString(receiptDir.resolve("pending").resolve("안형준_" + firstId + ".txt")))
                .startsWith("ORDER_ID|USER_ID|NAME|ITEM_ID|ITEM_NAME|PRICE\n" + firstId + "|");

        // 3. 운송 연계 배치 1회
        ShipmentResult result = shipmentFlow.run();

        assertThat(result).isEqualTo(new ShipmentResult(63, 63, 0, 0, false));
        assertThat(count("SELECT COUNT(*) FROM ORDER_TB WHERE APPLICANT_KEY = ? AND STATUS = 'N'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM SHIPMENT_TB WHERE APPLICANT_KEY = ?")).isEqualTo(shipmentsBefore + 63);

        // 4. 한 번 더 돌려도 중복 운송 없음
        assertThat(shipmentFlow.run().target()).isZero();
    }

    private byte[] sampleData() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/sample-order.xml")) {
            return in.readAllBytes();
        }
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = real.prepareStatement(sql)) {
            ps.setString(1, applicantKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // commit·close 를 무시하는 연결: 흐름 코드는 그대로 두고 테스트가 끝날 때 한 번에 rollback 할 수 있다
    private static Connection neverCommits(Connection real) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "commit", "close", "setAutoCommit" -> {
                            return null;
                        }
                        default -> {
                            try {
                                return method.invoke(real, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        }
                    }
                });
    }
}
